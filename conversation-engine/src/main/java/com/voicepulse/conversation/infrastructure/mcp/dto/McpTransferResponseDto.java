package com.voicepulse.conversation.infrastructure.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Transport response DTO returned by the {@code transfer_to_human_operator} tool on {@code
 * mcp-telephony-routing} (RFC-0001 §4.4 & RFC-0003 §4.3).
 */
public record McpTransferResponseDto(
        @JsonProperty("call_id") String callId,
        @JsonProperty("ticket_id") String ticketId,
        @JsonProperty("department") String department,
        @JsonProperty("status") String status,
        @JsonProperty("estimated_wait_seconds") int estimatedWaitSeconds,
        @JsonProperty("queue_position") int queuePosition,
        @JsonProperty("dispatched_at") Instant dispatchedAt) {}
