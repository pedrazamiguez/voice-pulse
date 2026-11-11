package com.voicepulse.shared.event;

import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain event envelope representing a completed airline disruption ticket form ready for
 * priority operator queue handoff.
 *
 * <p>Published to {@code telephony.tickets.intent} partitioned strictly by {@link
 * CallId#toString()} per RFC-0001 §4.2 and RFC-0003 §4.
 */
public record TicketFormFilledEvent(
        CallId callId,
        UUID ticketId,
        String passengerName,
        Pnr pnr,
        FlightNumber originalFlightNumber,
        DisruptionReason disruptionReason,
        RebookingPreference rebookingPreference,
        boolean hotelRequired,
        boolean specialAssistance,
        String targetSkillGroup,
        Map<String, Object> payload,
        Instant timestamp) {

    public TicketFormFilledEvent {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(passengerName, "passengerName must not be null");
        Objects.requireNonNull(pnr, "pnr must not be null");
        Objects.requireNonNull(originalFlightNumber, "originalFlightNumber must not be null");
        Objects.requireNonNull(disruptionReason, "disruptionReason must not be null");
        Objects.requireNonNull(rebookingPreference, "rebookingPreference must not be null");
        Objects.requireNonNull(targetSkillGroup, "targetSkillGroup must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        if (passengerName.isBlank()) {
            throw new IllegalArgumentException("passengerName must not be blank");
        }
        if (targetSkillGroup.isBlank()) {
            throw new IllegalArgumentException("targetSkillGroup must not be blank");
        }
        payload = (payload == null) ? Map.of() : Map.copyOf(payload);
    }

    @Override
    public Map<String, Object> payload() {
        return (payload == null) ? Map.of() : Map.copyOf(payload);
    }
}
