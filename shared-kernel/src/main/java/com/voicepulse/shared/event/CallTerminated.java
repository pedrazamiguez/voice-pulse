package com.voicepulse.shared.event;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;

/** Signaling event emitted when a call session is terminated or disconnected. */
public record CallTerminated(CallId callId, long sequenceNumber, Instant timestamp, String reason)
        implements CallControlEnvelope {

    public CallTerminated {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");

        if (sequenceNumber < 0) {
            throw new IllegalArgumentException(
                    "sequenceNumber cannot be negative: " + sequenceNumber);
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason cannot be blank");
        }
    }
}
