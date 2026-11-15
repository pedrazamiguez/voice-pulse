package com.voicepulse.routing.infrastructure.redis.model;

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
        operatorId = requireNonBlank(operatorId, "operatorId");
        displayName = requireNonBlank(displayName, "displayName");
        status = requireNonBlank(status, "status");
        skills = requireNonBlank(skills, "skills");
        activeCallId = activeCallId == null ? "" : activeCallId.trim();
        lastHeartbeatAt = requireNonBlank(lastHeartbeatAt, "lastHeartbeatAt");
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        return value.trim();
    }
}
