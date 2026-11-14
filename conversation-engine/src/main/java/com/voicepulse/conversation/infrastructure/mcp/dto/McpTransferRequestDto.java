package com.voicepulse.conversation.infrastructure.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Transport request DTO for the {@code transfer_to_human_operator} tool on {@code
 * mcp-telephony-routing} (RFC-0001 §4.4 & RFC-0003 §4.3).
 */
public record McpTransferRequestDto(
        @JsonProperty("call_id") String callId,
        @JsonProperty("department") String department,
        @JsonProperty("ticket_id") String ticketId) {

    public McpTransferRequestDto {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(department, "department must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");

        if (callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (department.isBlank()) {
            throw new IllegalArgumentException("department must not be blank");
        }
        if (ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId must not be blank");
        }
    }
}
