package com.voicepulse.conversation.domain.model;

import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain record representing an airline disruption ticket.
 *
 * <p>Enforces strict business invariants per RFC-0003 and AGENTS.md §4. Contains zero framework
 * annotations or external dependencies.
 */
public record AirlineDisruptionTicket(
        UUID ticketId,
        UUID callId,
        String passengerName,
        String pnr,
        String originalFlightNumber,
        DisruptionReason disruptionReason,
        RebookingPreference rebookingPreference,
        boolean hotelRequired,
        boolean specialAssistance,
        String status,
        Map<String, Object> payload,
        Instant createdAt,
        Instant updatedAt) {

    public AirlineDisruptionTicket {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(passengerName, "passengerName must not be null");
        Objects.requireNonNull(pnr, "pnr must not be null");
        Objects.requireNonNull(originalFlightNumber, "originalFlightNumber must not be null");
        Objects.requireNonNull(disruptionReason, "disruptionReason must not be null");
        Objects.requireNonNull(rebookingPreference, "rebookingPreference must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (!pnr.matches("^[A-Za-z0-9]{6}$")) {
            throw new IllegalArgumentException(
                    "PNR must be exactly 6 alphanumeric characters: " + pnr);
        }
        payload = (payload == null) ? Map.of() : Map.copyOf(payload);
    }

    @Override
    public Map<String, Object> payload() {
        return (payload == null) ? Map.of() : Map.copyOf(payload);
    }
}
