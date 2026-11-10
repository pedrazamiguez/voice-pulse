package com.voicepulse.shared.event;

import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable domain record representing an ingested chunk of raw binary PCM audio streamed across
 * the telephony pipeline.
 *
 * <p>Enforces sample alignment, DoS size boundaries, and defensive cloning.
 */
public record AudioChunkEnvelope(
        CallId callId, long sequenceNumber, Instant timestamp, AudioFormat format, byte[] payload) {

    /** Maximum permissible raw audio payload size in bytes (64 KB DoS defense boundary). */
    public static final int MAX_PAYLOAD_SIZE_BYTES = 65536;

    public AudioChunkEnvelope {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(format, "format cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");

        if (sequenceNumber < 0) {
            throw new IllegalArgumentException(
                    "sequenceNumber cannot be negative: " + sequenceNumber);
        }
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload cannot be empty");
        }
        if (payload.length > MAX_PAYLOAD_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "payload exceeds 64 KB DoS limit: " + payload.length);
        }
        if (payload.length % format.bytesPerSample() != 0) {
            throw new IllegalArgumentException(
                    "payload is not sample-aligned: length "
                            + payload.length
                            + " not divisible by "
                            + format.bytesPerSample());
        }

        payload = payload.clone();
    }

    /**
     * Constructs an {@link AudioChunkEnvelope} from a domain {@link AudioChunk}.
     *
     * @param chunk source domain chunk
     * @return equivalent audio event envelope
     */
    public static AudioChunkEnvelope from(AudioChunk chunk) {
        Objects.requireNonNull(chunk, "chunk cannot be null");
        return new AudioChunkEnvelope(
                chunk.sessionId().toCallId(),
                chunk.sequenceNumber(),
                chunk.timestamp(),
                chunk.format(),
                chunk.pcmData());
    }

    /**
     * Converts this envelope to a domain {@link AudioChunk}.
     *
     * @return equivalent domain audio chunk
     */
    public AudioChunk toAudioChunk() {
        return new AudioChunk(
                callId.toSessionId(), sequenceNumber, timestamp, payload.clone(), format);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AudioChunkEnvelope that)) {
            return false;
        }
        return sequenceNumber == that.sequenceNumber
                && Objects.equals(callId, that.callId)
                && Objects.equals(timestamp, that.timestamp)
                && Objects.equals(format, that.format)
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(callId, sequenceNumber, timestamp, format);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return "AudioChunkEnvelope[callId="
                + callId
                + ", sequenceNumber="
                + sequenceNumber
                + ", timestamp="
                + timestamp
                + ", format="
                + format
                + ", payloadBytes="
                + payload.length
                + "]";
    }
}
