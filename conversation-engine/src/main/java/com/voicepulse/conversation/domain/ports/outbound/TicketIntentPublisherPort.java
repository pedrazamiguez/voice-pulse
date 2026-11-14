package com.voicepulse.conversation.domain.ports.outbound;

import com.voicepulse.shared.event.TicketFormFilledEvent;

/**
 * Driven (outbound) port for publishing finalized disruption ticket events to {@code
 * telephony.tickets.intent} for downstream routing and persistence (RFC-0001 §4.2).
 */
public interface TicketIntentPublisherPort {

    /**
     * Publishes a {@link TicketFormFilledEvent} partitioned strictly by {@code
     * event.callId().toString()}.
     *
     * @param event finalized ticket event envelope
     */
    void publishTicketFilled(TicketFormFilledEvent event);
}
