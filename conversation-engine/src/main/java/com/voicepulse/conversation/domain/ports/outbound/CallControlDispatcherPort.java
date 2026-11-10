package com.voicepulse.conversation.domain.ports.outbound;

import com.voicepulse.shared.event.CallControlEnvelope;

/**
 * Driven (outbound) port for dispatching call control signaling events (e.g. barge-in interrupts,
 * session termination) to external infrastructure.
 */
public interface CallControlDispatcherPort {

    /**
     * Dispatches a call control envelope to downstream consumers (e.g. Kafka control topic).
     *
     * @param envelope call control signaling envelope
     */
    void dispatch(CallControlEnvelope envelope);
}
