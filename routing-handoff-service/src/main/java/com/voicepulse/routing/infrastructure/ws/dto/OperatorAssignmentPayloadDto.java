package com.voicepulse.routing.infrastructure.ws.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rich assignment and disruption ticket payload delivered to the assigned operator's Web Console
 * over WebSocket and REST endpoints.
 */
public record OperatorAssignmentPayloadDto(
        String callId,
        String ticketId,
        String operatorId,
        String skillGroup,
        String bridgeEndpoint,
        String bridgeUrl,
        Instant assignedAt,
        String passengerName,
        String pnr,
        String originalFlightNumber,
        String disruptionReason,
        String rebookingPreference,
        boolean hotelRequired,
        boolean specialAssistance,
        String summary,
        String priority,
        String category,
        String callerNumber,
        List<TranscriptTurnDto> transcript,
        Map<String, Object> metadata) {

    public OperatorAssignmentPayloadDto {
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        metadata =
                metadata == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    @Override
    public List<TranscriptTurnDto> transcript() {
        return transcript == null ? List.of() : List.copyOf(transcript);
    }

    @Override
    public Map<String, Object> metadata() {
        return metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
