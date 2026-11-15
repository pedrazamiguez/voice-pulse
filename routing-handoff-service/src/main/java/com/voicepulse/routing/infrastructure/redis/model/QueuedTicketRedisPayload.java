package com.voicepulse.routing.infrastructure.redis.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * JSON-serializable Redis payload record representing a queued {@code TicketFormFilledEvent} item
 * stored inside {@code <prefix>:queues:items}.
 */
public record QueuedTicketRedisPayload(
        String callId,
        String ticketId,
        String passengerName,
        String pnr,
        String originalFlightNumber,
        String disruptionReason,
        String rebookingPreference,
        boolean hotelRequired,
        boolean specialAssistance,
        String targetSkillGroup,
        Map<String, Object> payload,
        Instant eventTimestamp,
        String skillGroup,
        int priorityScore,
        Instant enqueuedAt) {

    public QueuedTicketRedisPayload {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(passengerName, "passengerName must not be null");
        Objects.requireNonNull(pnr, "pnr must not be null");
        Objects.requireNonNull(originalFlightNumber, "originalFlightNumber must not be null");
        Objects.requireNonNull(disruptionReason, "disruptionReason must not be null");
        Objects.requireNonNull(rebookingPreference, "rebookingPreference must not be null");
        Objects.requireNonNull(targetSkillGroup, "targetSkillGroup must not be null");
        Objects.requireNonNull(eventTimestamp, "eventTimestamp must not be null");
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
