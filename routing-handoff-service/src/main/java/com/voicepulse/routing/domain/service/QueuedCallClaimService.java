package com.voicepulse.routing.domain.service;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.ports.inbound.ClaimQueuedCallUseCase;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Pure domain service implementing manual operator claim operations on waiting priority queues. */
public class QueuedCallClaimService implements ClaimQueuedCallUseCase {

    private final PriorityCallQueuePort priorityCallQueuePort;
    private final QueuedCallDrainService queuedCallDrainService;

    public QueuedCallClaimService(
            PriorityCallQueuePort priorityCallQueuePort,
            QueuedCallDrainService queuedCallDrainService) {
        this.priorityCallQueuePort =
                Objects.requireNonNull(
                        priorityCallQueuePort, "priorityCallQueuePort cannot be null");
        this.queuedCallDrainService =
                Objects.requireNonNull(
                        queuedCallDrainService, "queuedCallDrainService cannot be null");
    }

    @Override
    public Optional<HandoffAssignment> claimNextQueuedCall(
            OperatorId operatorId, Instant claimedAt) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(claimedAt, "claimedAt cannot be null");

        Optional<OperatorPresence> operator =
                queuedCallDrainService.findAvailableOperator(operatorId);
        if (operator.isEmpty()) {
            return Optional.empty();
        }
        return queuedCallDrainService.drainNextForSkills(
                operatorId, operator.get().skills(), claimedAt);
    }

    @Override
    public Optional<HandoffAssignment> claimSpecificCall(
            OperatorId operatorId, CallId callId, Instant claimedAt) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(claimedAt, "claimedAt cannot be null");

        Optional<OperatorPresence> operator =
                queuedCallDrainService.findAvailableOperator(operatorId);
        if (operator.isEmpty()) {
            return Optional.empty();
        }

        Optional<QueuedTicketItem> removedItem = priorityCallQueuePort.removeByCallId(callId);
        if (removedItem.isEmpty()) {
            return Optional.empty();
        }
        return queuedCallDrainService.claimQueuedItem(operatorId, removedItem.get(), claimedAt);
    }
}
