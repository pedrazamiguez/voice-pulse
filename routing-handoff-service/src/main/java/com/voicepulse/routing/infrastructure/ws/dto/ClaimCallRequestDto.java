package com.voicepulse.routing.infrastructure.ws.dto;

/**
 * Optional REST request payload for {@code POST /api/operators/{operatorId}/claim}. When {@code
 * callId} is omitted or blank, the highest-priority waiting call matching the operator's skills is
 * claimed.
 */
public record ClaimCallRequestDto(String callId) {}
