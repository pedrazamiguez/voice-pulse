package com.voicepulse.conversation.domain.model;

import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain record representing the confirmation receipt returned by {@code
 * mcp-ticket-server} upon finalizing an {@link AirlineDisruptionTicket} (RFC-0003 §4.2).
 */
public record TicketSubmissionReceipt(
        String ticketId,
        Pnr pnr,
        String category,
        String priority,
        String status,
        Instant submittedAt) {

    public TicketSubmissionReceipt {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(pnr, "pnr must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(priority, "priority must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(submittedAt, "submittedAt must not be null");

        if (ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId must not be blank");
        }
        if (category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        if (priority.isBlank()) {
            throw new IllegalArgumentException("priority must not be blank");
        }
        if (status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
    }
}
