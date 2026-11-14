package com.voicepulse.conversation.infrastructure.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Transport response DTO returned by the {@code check_queue_status} tool on {@code
 * mcp-telephony-routing} (RFC-0001 §4.4).
 */
public record McpQueueStatusResponseDto(
        @JsonProperty("department") String department,
        @JsonProperty("available_operators") int availableOperators,
        @JsonProperty("estimated_wait_seconds") int estimatedWaitSeconds,
        @JsonProperty("queue_position") int queuePosition) {}
