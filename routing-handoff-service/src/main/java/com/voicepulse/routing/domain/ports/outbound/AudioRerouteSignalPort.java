package com.voicepulse.routing.domain.ports.outbound;

import com.voicepulse.shared.event.OperatorRerouteSignal;

/**
 * Driven (outbound) SPI port for emitting {@link OperatorRerouteSignal} control events to {@code
 * telephony.calls.control} partitioned strictly by {@code callId.toString()}.
 */
public interface AudioRerouteSignalPort {

    /**
     * Dispatches an {@link OperatorRerouteSignal} to instruct {@code edge-gateway-service} to
     * bridge caller audio to the assigned operator WebSocket.
     *
     * @param signal operator reroute control envelope
     */
    void sendRerouteSignal(OperatorRerouteSignal signal);
}
