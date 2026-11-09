package com.voicepulse.shared.event;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;

/**
 * Signaling event emitted to communicate downstream pipeline buffer watermarks and flow control.
 */
public record BackpressureSignal(
        CallId callId,
        long sequenceNumber,
        Instant timestamp,
        boolean pauseRequested,
        int bufferFillRatioPercent)
        implements CallControlEnvelope {

    public BackpressureSignal {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        if (sequenceNumber < 0) {
            throw new IllegalArgumentException(
                    "sequenceNumber cannot be negative: " + sequenceNumber);
        }
        if (bufferFillRatioPercent < 0 || bufferFillRatioPercent > 100) {
            throw new IllegalArgumentException(
                    "bufferFillRatioPercent must be between 0 and 100: " + bufferFillRatioPercent);
        }
    }

    /**
     * Convenience constructor setting default watermark ratio based on pause requested status.
     *
     * @param callId call identifier
     * @param sequenceNumber sequence number
     * @param timestamp event timestamp
     * @param pauseRequested true if ingestion should pause; false to resume
     */
    public BackpressureSignal(
            CallId callId, long sequenceNumber, Instant timestamp, boolean pauseRequested) {
        this(callId, sequenceNumber, timestamp, pauseRequested, pauseRequested ? 100 : 0);
    }
}
