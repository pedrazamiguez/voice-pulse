package com.voicepulse.routing.infrastructure.ws.dto;

import java.time.Instant;

/**
 * DTO projecting the sealed {@code HandoffLifecycleState} hierarchy for WebSocket and REST JSON.
 */
public record HandoffStateDto(
        String status,
        String callId,
        String ticketId,
        String operatorId,
        String skillGroup,
        Integer queuePosition,
        String bridgeEndpoint,
        String bridgeUrl,
        String resolutionNotes,
        String reason,
        Instant occurredAt) {}
