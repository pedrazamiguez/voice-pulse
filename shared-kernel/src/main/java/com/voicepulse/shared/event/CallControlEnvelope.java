package com.voicepulse.shared.event;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;

/**
 * Sealed interface representing call lifecycle and real-time signaling control events.
 *
 * <p>Transmitted over {@code telephony.calls.control} and partitioned strictly by {@link
 * #callId()}.
 */
public sealed interface CallControlEnvelope
        permits CallStarted,
                CallTerminated,
                BargeInInterrupt,
                BackpressureSignal,
                OperatorRerouteSignal {

    /** Unique call session identifier used for Kafka partitioning. */
    CallId callId();

    /** Monotonically increasing sequence number for ordering validation. */
    long sequenceNumber();

    /** UTC timestamp of the signaling event. */
    Instant timestamp();
}
