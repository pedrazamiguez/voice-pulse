package com.voicepulse.routing.infrastructure.persistence.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable persistence row record mirroring the PostgreSQL {@code disruption_tickets} table
 * ({@code config/postgres/init-db.sql}).
 */
public record DisruptionTicketRow(
        String ticketId,
        UUID callId,
        String pnr,
        String passengerName,
        String originalFlightNumber,
        String disruptionReason,
        String rebookingPreference,
        boolean hotelRequired,
        boolean specialAssistance,
        String status,
        String priority,
        String summary,
        Map<String, Object> payload,
        Instant createdAt,
        Instant updatedAt) {

    public DisruptionTicketRow {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(pnr, "pnr must not be null");
        Objects.requireNonNull(passengerName, "passengerName must not be null");
        Objects.requireNonNull(originalFlightNumber, "originalFlightNumber must not be null");
        Objects.requireNonNull(disruptionReason, "disruptionReason must not be null");
        Objects.requireNonNull(rebookingPreference, "rebookingPreference must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(priority, "priority must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId must not be blank");
        }
        payload =
                (payload == null)
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    @Override
    public Map<String, Object> payload() {
        return (payload == null)
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
