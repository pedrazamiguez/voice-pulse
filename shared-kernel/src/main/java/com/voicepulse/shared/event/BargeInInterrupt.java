package com.voicepulse.shared.event;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;

/**
 * Signaling event emitted when user speech is detected during agent TTS playback (barge-in).
 *
 * <p>Instructs downstream audio sinks and edge gateways to purge queued playback buffers.
 */
public record BargeInInterrupt(
        CallId callId, long sequenceNumber, Instant timestamp, long playbackOffsetMs)
        implements CallControlEnvelope {

    public BargeInInterrupt {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        if (sequenceNumber < 0) {
            throw new IllegalArgumentException(
                    "sequenceNumber cannot be negative: " + sequenceNumber);
        }
        if (playbackOffsetMs < 0) {
            throw new IllegalArgumentException(
                    "playbackOffsetMs cannot be negative: " + playbackOffsetMs);
        }
    }

    /**
     * Convenience constructor with default zero playback offset.
     *
     * @param callId call identifier
     * @param sequenceNumber sequence number
     * @param timestamp event timestamp
     */
    public BargeInInterrupt(CallId callId, long sequenceNumber, Instant timestamp) {
        this(callId, sequenceNumber, timestamp, 0L);
    }
}
