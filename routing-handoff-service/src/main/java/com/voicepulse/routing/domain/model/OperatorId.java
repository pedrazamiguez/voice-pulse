package com.voicepulse.routing.domain.model;

import java.util.Objects;

/**
 * Strongly-typed domain identifier for a human call-center operator.
 *
 * <p>Bounded to 64 characters without whitespace so it maps safely to PostgreSQL {@code
 * VARCHAR(64)} columns, Redis keys, and WebSocket audio bridge endpoints.
 */
public record OperatorId(String value) {

    private static final int MAX_LENGTH = 64;

    public OperatorId {
        Objects.requireNonNull(value, "operatorId cannot be null");
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("operatorId cannot be blank");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "operatorId exceeds max length of " + MAX_LENGTH + ": " + trimmed.length());
        }
        if (trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "operatorId must not contain whitespace: " + trimmed);
        }
        value = trimmed;
    }

    public static OperatorId fromString(String value) {
        return new OperatorId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
