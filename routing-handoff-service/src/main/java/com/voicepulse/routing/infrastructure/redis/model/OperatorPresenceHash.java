package com.voicepulse.routing.infrastructure.redis.model;

import java.util.Objects;

/**
 * Infrastructure Redis Hash representation of an operator's live presence snapshot stored under
 * {@code <prefix>:operators:<operatorId>:presence}.
 */
public record OperatorPresenceHash(
        String operatorId,
        String displayName,
        String status,
        String skills,
        String activeCallId,
        String lastHeartbeatAt) {

    public OperatorPresenceHash {
        Objects.requireNonNull(operatorId, "operatorId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(skills, "skills must not be null");
        Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt must not be null");
        activeCallId = activeCallId == null ? "" : activeCallId.trim();
    }
}
