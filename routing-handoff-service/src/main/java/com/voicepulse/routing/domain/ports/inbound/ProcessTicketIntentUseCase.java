package com.voicepulse.routing.domain.ports.inbound;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.shared.event.TicketFormFilledEvent;

/**
 * Driving (inbound) port triggered when a finalized {@link TicketFormFilledEvent} arrives from
 * {@code telephony.tickets.intent}.
 */
public interface ProcessTicketIntentUseCase {

    /**
     * Persists the finalized disruption ticket and either assigns an available skill-matched
     * operator or enqueues the call in the priority queue.
     *
     * @param event finalized ticket event from the conversation engine
     * @return resulting handoff lifecycle state ({@code Assigned} or {@code Queued})
     */
    HandoffLifecycleState processTicketIntent(TicketFormFilledEvent event);
}
