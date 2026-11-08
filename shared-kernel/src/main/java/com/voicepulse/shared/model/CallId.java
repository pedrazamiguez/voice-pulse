package com.voicepulse.shared.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly-typed domain identifier for a call session.
 *
 * <p>Used as the Kafka partition key and aggregate identifier across all VoicePulse pipelines.
 */
public record CallId(UUID value) {

    public CallId {
        Objects.requireNonNull(value, "callId UUID cannot be null");
    }

    public static CallId generate() {
        return new CallId(UUID.randomUUID());
    }

    public static CallId fromString(String uuidStr) {
        Objects.requireNonNull(uuidStr, "callId string cannot be null");
        return new CallId(UUID.fromString(uuidStr));
    }

    public static CallId fromSessionId(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        return new CallId(sessionId.value());
    }

    public CallSessionId toSessionId() {
        return new CallSessionId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
