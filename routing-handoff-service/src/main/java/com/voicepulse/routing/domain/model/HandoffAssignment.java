package com.voicepulse.routing.domain.model;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain record capturing the confirmed binding between a queued/incoming call and an
 * assigned human operator.
 */
public record HandoffAssignment(
        CallId callId,
        UUID ticketId,
        OperatorId operatorId,
        SkillGroup skillGroup,
        String bridgeEndpoint,
        Instant assignedAt) {

    public HandoffAssignment {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(ticketId, "ticketId cannot be null");
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(skillGroup, "skillGroup cannot be null");
        Objects.requireNonNull(bridgeEndpoint, "bridgeEndpoint cannot be null");
        Objects.requireNonNull(assignedAt, "assignedAt cannot be null");

        String trimmedEndpoint = bridgeEndpoint.trim();
        if (trimmedEndpoint.isBlank()) {
            throw new IllegalArgumentException("bridgeEndpoint cannot be blank");
        }
        bridgeEndpoint = trimmedEndpoint;
    }
}
