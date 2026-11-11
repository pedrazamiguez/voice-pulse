package com.voicepulse.conversation.infrastructure.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Transport response DTO returned by the {@code finalize_ticket} tool on {@code mcp-ticket-server}
 * (RFC-0003 §4.2).
 */
public record McpFinalizeTicketResponseDto(
        @JsonProperty("ticket_id") String ticketId,
        @JsonProperty("pnr") String pnr,
        @JsonProperty("category") String category,
        @JsonProperty("priority") String priority,
        @JsonProperty("status") String status,
        @JsonProperty("submitted_at") Instant submittedAt) {}
