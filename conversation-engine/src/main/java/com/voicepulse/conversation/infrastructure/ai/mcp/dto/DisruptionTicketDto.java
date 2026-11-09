package com.voicepulse.conversation.infrastructure.ai.mcp.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Transport data transfer object for disruption tickets exchanged over MCP or external APIs. */
public record DisruptionTicketDto(
        UUID ticketId,
        UUID callId,
        String passengerName,
        String pnr,
        String originalFlightNumber,
        String disruptionReason,
        String rebookingPreference,
        boolean hotelRequired,
        boolean specialAssistance,
        String status,
        Map<String, Object> payload,
        Instant createdAt,
        Instant updatedAt) {

    public DisruptionTicketDto {
        payload = (payload == null) ? Map.of() : Map.copyOf(payload);
    }

    @Override
    public Map<String, Object> payload() {
        return (payload == null) ? Map.of() : Map.copyOf(payload);
    }
}
