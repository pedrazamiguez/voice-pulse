package com.voicepulse.shared.model;

import java.time.Instant;
import java.util.Arrays;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AudioChunk that)) {
            return false;
        }
        return sequenceNumber == that.sequenceNumber
                && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(timestamp, that.timestamp)
                && Arrays.equals(pcmData, that.pcmData)
                && Objects.equals(format, that.format);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sessionId, sequenceNumber, timestamp, format);
        result = 31 * result + Arrays.hashCode(pcmData);
        return result;
    }

    @Override
    public String toString() {
        return "AudioChunk[sessionId="
                + sessionId
                + ", sequenceNumber="
                + sequenceNumber
                + ", timestamp="
                + timestamp
                + ", pcmDataBytes="
                + pcmData.length
                + ", format="
                + format
                + "]";
    }
}
