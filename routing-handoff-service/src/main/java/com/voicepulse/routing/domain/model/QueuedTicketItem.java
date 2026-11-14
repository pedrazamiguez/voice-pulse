package com.voicepulse.routing.domain.model;

import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain entry representing a finalized disruption ticket awaiting operator assignment in
 * a prioritized {@link SkillGroup} queue.
 */
public record QueuedTicketItem(
        TicketFormFilledEvent ticketEvent,
        SkillGroup skillGroup,
        int priorityScore,
        Instant enqueuedAt) {

    public QueuedTicketItem {
        Objects.requireNonNull(ticketEvent, "ticketEvent cannot be null");
        Objects.requireNonNull(skillGroup, "skillGroup cannot be null");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt cannot be null");

        if (priorityScore < 0) {
            throw new IllegalArgumentException(
                    "priorityScore cannot be negative: " + priorityScore);
        }
    }

    /** Returns the call identifier associated with this queued ticket. */
    public CallId callId() {
        return ticketEvent.callId();
    }

    /** Returns the ticket identifier associated with this queued ticket. */
    public UUID ticketId() {
        return ticketEvent.ticketId();
    }
}
