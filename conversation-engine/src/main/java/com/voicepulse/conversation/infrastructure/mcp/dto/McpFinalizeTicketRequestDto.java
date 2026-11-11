package com.voicepulse.conversation.infrastructure.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Transport request DTO for the {@code finalize_ticket} tool on {@code mcp-ticket-server} (RFC-0003
 * §4.2).
 */
public record McpFinalizeTicketRequestDto(
        @JsonProperty("ticket_id") String ticketId,
        @JsonProperty("pnr") String pnr,
        @JsonProperty("category") String category,
        @JsonProperty("priority") String priority,
        @JsonProperty("summary") String summary,
        @JsonProperty("extracted_entities") McpExtractedEntitiesDto extractedEntities) {

    public McpFinalizeTicketRequestDto {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(pnr, "pnr must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(priority, "priority must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(extractedEntities, "extractedEntities must not be null");

        if (ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId must not be blank");
        }
        if (pnr.isBlank()) {
            throw new IllegalArgumentException("pnr must not be blank");
        }
        if (category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        if (priority.isBlank()) {
            throw new IllegalArgumentException("priority must not be blank");
        }
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
    }
}
