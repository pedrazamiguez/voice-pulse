package com.voicepulse.routing.domain.service;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffAuditEventType;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.HandoffTicketStatus;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillMatchPlan;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure domain coordinator responsible for durable ticket persistence, structured audit logging, and
 * compensating rollbacks across Redis presence and priority queues when persistence fails.
 */
public class HandoffPersistenceCoordinator {

    private final TicketPersistencePort ticketPersistencePort;
    private final OperatorPresencePort operatorPresencePort;
    private final PriorityCallQueuePort priorityCallQueuePort;
    private final Clock clock;

    public HandoffPersistenceCoordinator(
            TicketPersistencePort ticketPersistencePort,
            OperatorPresencePort operatorPresencePort,
            PriorityCallQueuePort priorityCallQueuePort,
            Clock clock) {
        this.ticketPersistencePort =
                Objects.requireNonNull(
                        ticketPersistencePort, "ticketPersistencePort cannot be null");
        this.operatorPresencePort =
                Objects.requireNonNull(operatorPresencePort, "operatorPresencePort cannot be null");
        this.priorityCallQueuePort =
                Objects.requireNonNull(
                        priorityCallQueuePort, "priorityCallQueuePort cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /**
     * Persists an immediately assigned ticket and audit record, releasing the claimed operator as
     * compensation if persistence fails.
     */
    public void saveImmediateAssignment(
            TicketFormFilledEvent event,
            SkillMatchPlan plan,
            HandoffAssignment assignment,
            HandoffLifecycleState.Assigned assignedState,
            Instant occurredAt) {
        try {
            ticketPersistencePort.saveTicket(event, plan.primarySkillGroup(), assignedState);
            ticketPersistencePort.recordHandoffAuditLog(
                    event.callId(),
                    HandoffAuditEventType.OPERATOR_ASSIGNED.name(),
                    null,
                    HandoffTicketStatus.ASSIGNED.name(),
                    assignment.operatorId(),
                    Map.of(
                            "ticketId", assignment.ticketId().toString(),
                            "skillGroup", assignment.skillGroup().name(),
                            "bridgeEndpoint", assignment.bridgeEndpoint(),
                            "priorityScore", plan.priorityScore()),
                    occurredAt);
        } catch (RuntimeException ex) {
            operatorPresencePort.releaseOperator(
                    assignment.operatorId(), OperatorAvailabilityStatus.AVAILABLE, clock.instant());
            throw ex;
        }
    }

    /**
     * Persists a queued ticket and audit record, removing the queued call entry as compensation if
     * persistence fails.
     */
    public void saveQueuedTicket(
            TicketFormFilledEvent event,
            SkillMatchPlan plan,
            HandoffLifecycleState.Queued queuedState,
            int queuePosition,
            Instant occurredAt) {
        try {
            ticketPersistencePort.saveTicket(event, plan.primarySkillGroup(), queuedState);
            ticketPersistencePort.recordHandoffAuditLog(
                    event.callId(),
                    HandoffAuditEventType.TICKET_QUEUED.name(),
                    null,
                    HandoffTicketStatus.QUEUED.name(),
                    null,
                    Map.of(
                            "skillGroup", plan.primarySkillGroup().name(),
                            "priorityScore", plan.priorityScore(),
                            "queuePosition", queuePosition),
                    occurredAt);
        } catch (RuntimeException ex) {
            priorityCallQueuePort.removeByCallId(event.callId());
            throw ex;
        }
    }

    /**
     * Persists a queued-call assignment transition, releasing the operator and re-enqueueing the
     * item as compensation if persistence fails.
     */
    public void recordQueuedClaim(
            QueuedTicketItem queuedItem, HandoffAssignment assignment, Instant claimedAt) {
        try {
            ticketPersistencePort.updateTicketStatus(
                    queuedItem.ticketId(), HandoffTicketStatus.ASSIGNED.name(), claimedAt);
            ticketPersistencePort.recordHandoffAuditLog(
                    queuedItem.callId(),
                    HandoffAuditEventType.OPERATOR_ASSIGNED.name(),
                    HandoffTicketStatus.QUEUED.name(),
                    HandoffTicketStatus.ASSIGNED.name(),
                    assignment.operatorId(),
                    Map.of(
                            "ticketId", assignment.ticketId().toString(),
                            "skillGroup", assignment.skillGroup().name(),
                            "bridgeEndpoint", assignment.bridgeEndpoint(),
                            "priorityScore", queuedItem.priorityScore()),
                    claimedAt);
        } catch (RuntimeException ex) {
            operatorPresencePort.releaseOperator(
                    assignment.operatorId(), OperatorAvailabilityStatus.AVAILABLE, clock.instant());
            priorityCallQueuePort.enqueue(queuedItem);
            throw ex;
        }
    }

    /** Updates ticket state and appends completion audit log, returning the resolved ticket ID. */
    public UUID recordHandoffCompletion(
            CallId callId, OperatorId operatorId, String notes, Instant completedAt) {
        Optional<TicketFormFilledEvent> persistedTicket =
                ticketPersistencePort.findTicketByCallId(callId);
        UUID ticketId = persistedTicket.map(TicketFormFilledEvent::ticketId).orElse(callId.value());

        if (persistedTicket.isPresent()) {
            ticketPersistencePort.updateTicketStatus(
                    ticketId, HandoffTicketStatus.COMPLETED.name(), completedAt);
        }
        ticketPersistencePort.recordHandoffAuditLog(
                callId,
                HandoffAuditEventType.HANDOFF_COMPLETED.name(),
                HandoffTicketStatus.ASSIGNED.name(),
                HandoffTicketStatus.COMPLETED.name(),
                operatorId,
                Map.of("ticketId", ticketId.toString(), "resolutionNotes", notes),
                completedAt);
        return ticketId;
    }

    /**
     * Updates ticket state and appends call-abandonment audit log, returning the resolved ticket
     * ID.
     */
    public UUID recordAbandonment(
            CallId callId,
            Optional<QueuedTicketItem> removedItem,
            String resolvedReason,
            Instant abandonedAt) {
        Optional<TicketFormFilledEvent> persistedTicket =
                ticketPersistencePort.findTicketByCallId(callId);
        UUID ticketId =
                removedItem
                        .map(QueuedTicketItem::ticketId)
                        .or(() -> persistedTicket.map(TicketFormFilledEvent::ticketId))
                        .orElse(callId.value());

        String sourceState = resolveAbandonSourceState(removedItem, persistedTicket);
        if (removedItem.isPresent() || persistedTicket.isPresent()) {
            ticketPersistencePort.updateTicketStatus(
                    ticketId, HandoffTicketStatus.ABANDONED.name(), abandonedAt);
        }
        ticketPersistencePort.recordHandoffAuditLog(
                callId,
                HandoffAuditEventType.CALL_ABANDONED.name(),
                sourceState,
                HandoffTicketStatus.ABANDONED.name(),
                null,
                Map.of("ticketId", ticketId.toString(), "reason", resolvedReason),
                abandonedAt);
        return ticketId;
    }

    private static String resolveAbandonSourceState(
            Optional<QueuedTicketItem> removedItem,
            Optional<TicketFormFilledEvent> persistedTicket) {
        if (removedItem.isPresent()) {
            return HandoffTicketStatus.QUEUED.name();
        }
        if (persistedTicket.isPresent()) {
            return HandoffTicketStatus.ASSIGNED.name();
        }
        return null;
    }
}
