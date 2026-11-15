package com.voicepulse.routing.domain.service;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.inbound.ManageOperatorPresenceUseCase;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure domain service implementing {@link ManageOperatorPresenceUseCase}, triggering automatic
 * skill-queue draining whenever an operator transitions to {@link
 * OperatorAvailabilityStatus#AVAILABLE}.
 */
public class OperatorPresenceService implements ManageOperatorPresenceUseCase {

    private final OperatorPresencePort operatorPresencePort;
    private final QueuedCallDrainService queuedCallDrainService;

    public OperatorPresenceService(
            OperatorPresencePort operatorPresencePort,
            QueuedCallDrainService queuedCallDrainService) {
        this.operatorPresencePort =
                Objects.requireNonNull(operatorPresencePort, "operatorPresencePort cannot be null");
        this.queuedCallDrainService =
                Objects.requireNonNull(
                        queuedCallDrainService, "queuedCallDrainService cannot be null");
    }

    @Override
    public OperatorPresence updatePresence(
            OperatorId operatorId,
            String displayName,
            OperatorAvailabilityStatus status,
            Set<SkillGroup> skills,
            Instant timestamp) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(displayName, "displayName cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(skills, "skills cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        Optional<OperatorPresence> existing = operatorPresencePort.findPresence(operatorId);
        Optional<CallId> retainedCall =
                status == OperatorAvailabilityStatus.AVAILABLE
                        ? Optional.empty()
                        : existing.flatMap(OperatorPresence::activeCallId);

        OperatorPresence updated =
                new OperatorPresence(
                        operatorId, displayName, status, skills, retainedCall, timestamp);
        operatorPresencePort.savePresence(updated);

        if (status == OperatorAvailabilityStatus.AVAILABLE) {
            Optional<HandoffAssignment> drained =
                    queuedCallDrainService.drainNextForSkills(
                            operatorId, updated.skills(), timestamp);
            if (drained.isPresent()) {
                return updated.assignCall(drained.get().callId(), timestamp);
            }
        }
        return updated;
    }

    @Override
    public Optional<OperatorPresence> refreshHeartbeat(OperatorId operatorId, Instant timestamp) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        if (!operatorPresencePort.refreshHeartbeat(operatorId, timestamp)) {
            return Optional.empty();
        }
        return operatorPresencePort
                .findPresence(operatorId)
                .map(presence -> presence.refreshHeartbeat(timestamp));
    }

    @Override
    public Optional<OperatorPresence> getPresence(OperatorId operatorId) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        return operatorPresencePort.findPresence(operatorId);
    }

    @Override
    public List<OperatorPresence> listOperatorsBySkillGroup(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup cannot be null");
        return operatorPresencePort.findBySkillGroup(skillGroup);
    }
}
