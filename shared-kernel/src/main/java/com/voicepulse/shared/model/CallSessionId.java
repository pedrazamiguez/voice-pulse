package com.voicepulse.shared.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly-typed domain identifier for a conversational call session.
 *
 * <p>Identifies active transport sessions and maps directly to {@link CallId}.
 */
public record CallSessionId(UUID value) {

    public CallSessionId {
        Objects.requireNonNull(value, "callSessionId UUID cannot be null");
    }

    public static CallSessionId generate() {
        return new CallSessionId(UUID.randomUUID());
    }

    public static CallSessionId fromString(String uuidStr) {
        Objects.requireNonNull(uuidStr, "callSessionId string cannot be null");
        return new CallSessionId(UUID.fromString(uuidStr));
    }

    public static CallSessionId fromCallId(CallId callId) {
        Objects.requireNonNull(callId, "callId cannot be null");
        return new CallSessionId(callId.value());
    }

    public CallId toCallId() {
        return new CallId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
