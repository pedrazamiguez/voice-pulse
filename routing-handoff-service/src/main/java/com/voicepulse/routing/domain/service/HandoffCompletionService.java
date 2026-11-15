package com.voicepulse.routing.domain.service;

import com.voicepulse.routing.domain.model.CallAbandonReason;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.ports.inbound.CompleteHandoffUseCase;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure domain service implementing terminal handoff lifecycle transitions ({@link #completeHandoff}
 * and {@link #markCallAbandoned}), releasing operators and draining queued calls upon completion.
 */
public class HandoffCompletionService implements CompleteHandoffUseCase {

    private final OperatorPresencePort operatorPresencePort;
    private final PriorityCallQueuePort priorityCallQueuePort;
    private final HandoffPersistenceCoordinator persistenceCoordinator;
    private final OperatorRerouteDispatcher dispatcher;
    private final QueuedCallDrainService queuedCallDrainService;

    public HandoffCompletionService(
            OperatorPresencePort operatorPresencePort,
            PriorityCallQueuePort priorityCallQueuePort,
            HandoffPersistenceCoordinator persistenceCoordinator,
            OperatorRerouteDispatcher dispatcher,
            QueuedCallDrainService queuedCallDrainService) {
        this.operatorPresencePort =
                Objects.requireNonNull(operatorPresencePort, "operatorPresencePort cannot be null");
        this.priorityCallQueuePort =
                Objects.requireNonNull(
                        priorityCallQueuePort, "priorityCallQueuePort cannot be null");
        this.persistenceCoordinator =
                Objects.requireNonNull(
                        persistenceCoordinator, "persistenceCoordinator cannot be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
        this.queuedCallDrainService =
                Objects.requireNonNull(
                        queuedCallDrainService, "queuedCallDrainService cannot be null");
    }

    @Override
    public HandoffLifecycleState completeHandoff(
            CallId callId, OperatorId operatorId, String resolutionNotes, Instant completedAt) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(completedAt, "completedAt cannot be null");

        String notes = resolutionNotes == null ? "" : resolutionNotes.trim();
        Optional<OperatorPresence> operatorPresence = operatorPresencePort.findPresence(operatorId);
        UUID ticketId =
                persistenceCoordinator.recordHandoffCompletion(
                        callId, operatorId, notes, completedAt);

        operatorPresencePort.releaseOperator(
                operatorId, OperatorAvailabilityStatus.AVAILABLE, completedAt);

        HandoffLifecycleState.Completed completedState =
                new HandoffLifecycleState.Completed(
                        callId, ticketId, operatorId, notes, completedAt);
        dispatcher.notifyLifecycleState(completedState);

        operatorPresence.ifPresent(
                presence ->
                        queuedCallDrainService.drainNextForSkills(
                                operatorId, presence.skills(), completedAt));
        return completedState;
    }

    @Override
    public HandoffLifecycleState markCallAbandoned(
            CallId callId, String reason, Instant abandonedAt) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(abandonedAt, "abandonedAt cannot be null");

        String resolvedReason = CallAbandonReason.normalizeOrDefault(reason);
        Optional<QueuedTicketItem> removedItem = priorityCallQueuePort.removeByCallId(callId);
        removedItem.ifPresent(
                item ->
                        dispatcher.broadcastQueueDepth(
                                item.skillGroup(),
                                priorityCallQueuePort.queueDepth(item.skillGroup())));

        UUID ticketId =
                persistenceCoordinator.recordAbandonment(
                        callId, removedItem, resolvedReason, abandonedAt);
        HandoffLifecycleState.Abandoned abandonedState =
                new HandoffLifecycleState.Abandoned(callId, ticketId, resolvedReason, abandonedAt);
        dispatcher.notifyLifecycleState(abandonedState);
        return abandonedState;
    }
}
