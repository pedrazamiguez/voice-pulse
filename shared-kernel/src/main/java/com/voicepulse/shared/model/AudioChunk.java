package com.voicepulse.shared.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain record representing an ingested chunk of raw binary audio.
 *
 * <p>Enforces immutability via defensive array copying and validates size boundaries against DoS
 * attacks.
 */
public record AudioChunk(
        CallSessionId sessionId,
        long sequenceNumber,
        Instant timestamp,
        byte[] pcmData,
        AudioFormat format) {

    /** Maximum permissible raw audio chunk size in bytes (64 KB DoS defense boundary). */
    public static final int MAX_CHUNK_SIZE_BYTES = 65536;

    public AudioChunk {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(pcmData, "pcmData cannot be null");
        Objects.requireNonNull(format, "format cannot be null");

        if (sequenceNumber < 0) {
            throw new IllegalArgumentException(
                    "sequenceNumber cannot be negative: " + sequenceNumber);
        }
        if (pcmData.length == 0) {
            throw new IllegalArgumentException("pcmData cannot be empty");
        }
        if (pcmData.length > MAX_CHUNK_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "pcmData exceeds 64 KB DoS limit: " + pcmData.length);
        }
        if (pcmData.length % format.bytesPerSample() != 0) {
            throw new IllegalArgumentException(
                    "pcmData is not sample-aligned: length "
                            + pcmData.length
                            + " not divisible by "
                            + format.bytesPerSample());
        }

        pcmData = pcmData.clone();
    }

    @Override
    public byte[] pcmData() {
        return pcmData.clone();
    }
}
