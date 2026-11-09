package com.voicepulse.shared.event;

import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;

/** Signaling event emitted when a new call session is initiated and audio format is negotiated. */
public record CallStarted(CallId callId, long sequenceNumber, Instant timestamp, AudioFormat format)
        implements CallControlEnvelope {

    public CallStarted {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(format, "format cannot be null");

        if (sequenceNumber < 0) {
            throw new IllegalArgumentException(
                    "sequenceNumber cannot be negative: " + sequenceNumber);
        }
    }
}
