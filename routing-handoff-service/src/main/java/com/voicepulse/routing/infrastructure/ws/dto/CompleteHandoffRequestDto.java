package com.voicepulse.routing.infrastructure.ws.dto;

/**
 * Optional REST request payload for {@code POST
 * /api/operators/{operatorId}/calls/{callId}/complete}.
 */
public record CompleteHandoffRequestDto(String resolutionNotes) {}
