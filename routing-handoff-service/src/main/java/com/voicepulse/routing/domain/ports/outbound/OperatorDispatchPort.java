package com.voicepulse.routing.domain.ports.outbound;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.shared.event.TicketFormFilledEvent;

/**
 * Driven (outbound) SPI port for pushing real-time assignments, queue metrics, and lifecycle state
 * updates to the Operator Web Console.
 */
public interface OperatorDispatchPort {

    /**
     * Pushes a newly assigned call and its pre-populated disruption ticket to the assigned
     * operator's Web Console session.
     *
     * @param assignment confirmed operator assignment
     * @param ticketEvent finalized ticket context
     */
    void pushAssignment(HandoffAssignment assignment, TicketFormFilledEvent ticketEvent);

    /**
     * Broadcasts updated queue depth metrics for a skill group to connected Operator Web Consoles.
     *
     * @param skillGroup updated skill group queue
     * @param queueDepth current depth of the queue
     */
    void broadcastQueueMetrics(SkillGroup skillGroup, long queueDepth);

    /**
     * Publishes a handoff lifecycle state change notification to interested operator consoles.
     *
     * @param state resulting handoff lifecycle state
     */
    void notifyHandoffStateChanged(HandoffLifecycleState state);
}
