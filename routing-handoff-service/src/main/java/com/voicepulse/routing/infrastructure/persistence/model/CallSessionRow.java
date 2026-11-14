package com.voicepulse.routing.infrastructure.persistence.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable persistence row record mirroring the PostgreSQL {@code call_sessions} table ({@code
 * config/postgres/init-db.sql}).
 */
public record CallSessionRow(
        UUID callId,
        String pnr,
        String callerNumber,
        String status,
        Instant startTime,
        Instant endTime,
        Integer durationSeconds,
        String assignedOperatorId,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public CallSessionRow {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        metadata =
                (metadata == null)
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    @Override
    public Map<String, Object> metadata() {
        return (metadata == null)
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
