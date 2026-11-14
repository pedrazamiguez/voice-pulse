package com.voicepulse.routing.domain.model;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable domain snapshot of a human operator's presence, skill memberships, and active call
 * binding.
 */
public record OperatorPresence(
        OperatorId operatorId,
        String displayName,
        OperatorAvailabilityStatus status,
        Set<SkillGroup> skills,
        Optional<CallId> activeCallId,
        Instant lastHeartbeatAt) {

    public OperatorPresence {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(displayName, "displayName cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(skills, "skills cannot be null");
        Objects.requireNonNull(activeCallId, "activeCallId cannot be null");
        Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt cannot be null");

        String trimmedName = displayName.trim();
        if (trimmedName.isBlank()) {
            throw new IllegalArgumentException("displayName cannot be blank");
        }
        if (skills.isEmpty()) {
            throw new IllegalArgumentException("skills must contain at least one SkillGroup");
        }
        if (status == OperatorAvailabilityStatus.AVAILABLE && activeCallId.isPresent()) {
            throw new IllegalArgumentException(
                    "AVAILABLE operator cannot hold an activeCallId: " + activeCallId.get());
        }

        displayName = trimmedName;
        skills = Set.copyOf(skills);
    }

    /** Transitions this operator into {@code BUSY} state bound to {@code callId}. */
    public OperatorPresence assignCall(CallId callId, Instant assignedAt) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(assignedAt, "assignedAt cannot be null");
        return new OperatorPresence(
                operatorId,
                displayName,
                OperatorAvailabilityStatus.BUSY,
                skills,
                Optional.of(callId),
                assignedAt);
    }

    /** Releases any active call and updates the operator status to {@code nextStatus}. */
    public OperatorPresence releaseCall(OperatorAvailabilityStatus nextStatus, Instant releasedAt) {
        Objects.requireNonNull(nextStatus, "nextStatus cannot be null");
        Objects.requireNonNull(releasedAt, "releasedAt cannot be null");
        return new OperatorPresence(
                operatorId, displayName, nextStatus, skills, Optional.empty(), releasedAt);
    }

    /** Returns an updated presence with the requested availability status. */
    public OperatorPresence withStatus(OperatorAvailabilityStatus nextStatus, Instant updatedAt) {
        Objects.requireNonNull(nextStatus, "nextStatus cannot be null");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
        Optional<CallId> retainedCall =
                nextStatus == OperatorAvailabilityStatus.AVAILABLE
                        ? Optional.empty()
                        : activeCallId;
        return new OperatorPresence(
                operatorId, displayName, nextStatus, skills, retainedCall, updatedAt);
    }

    /** Returns an updated presence with a refreshed heartbeat timestamp. */
    public OperatorPresence refreshHeartbeat(Instant heartbeatAt) {
        Objects.requireNonNull(heartbeatAt, "heartbeatAt cannot be null");
        return new OperatorPresence(
                operatorId, displayName, status, skills, activeCallId, heartbeatAt);
    }
}
