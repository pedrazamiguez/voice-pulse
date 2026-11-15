package com.voicepulse.shared.event;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;

/**
 * Signaling event emitted when a call session is assigned to a live human operator and ready for
 * audio WebSocket bridging (RFC-0001 §4.5, RFC-0003 §4).
 *
 * <p>Transmitted over {@code telephony.calls.control} partitioned strictly by {@link CallId}.
 */
public record OperatorRerouteSignal(
        CallId callId,
        long sequenceNumber,
        Instant timestamp,
        String operatorId,
        String bridgeEndpoint)
        implements CallControlEnvelope {

    public OperatorRerouteSignal {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(bridgeEndpoint, "bridgeEndpoint cannot be null");

        if (sequenceNumber < 0) {
            throw new IllegalArgumentException(
                    "sequenceNumber cannot be negative: " + sequenceNumber);
        }
        if (operatorId.isBlank()) {
            throw new IllegalArgumentException("operatorId cannot be blank");
        }
        if (bridgeEndpoint.isBlank()) {
            throw new IllegalArgumentException("bridgeEndpoint cannot be blank");
        }
    }
}
