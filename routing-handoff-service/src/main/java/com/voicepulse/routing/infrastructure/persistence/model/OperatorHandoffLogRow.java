package com.voicepulse.routing.infrastructure.persistence.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable persistence row record mirroring the PostgreSQL {@code operator_handoff_logs} audit
 * table ({@code config/postgres/init-db.sql}).
 */
public record OperatorHandoffLogRow(
        UUID callId,
        String eventType,
        String sourceState,
        String targetState,
        String operatorId,
        Map<String, Object> details,
        Instant occurredAt) {

    public OperatorHandoffLogRow {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        details =
                (details == null)
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    @Override
    public Map<String, Object> details() {
        return (details == null)
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
