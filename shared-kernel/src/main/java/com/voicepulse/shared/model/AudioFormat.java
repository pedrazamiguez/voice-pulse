package com.voicepulse.shared.model;

import java.util.Objects;

/**
 * Immutable domain record defining acoustic audio characteristics.
 *
 * <p>Enforces telephony standards specified in RFC-0001 §2 and telephony invariants.
 */
public record AudioFormat(int sampleRateHz, int bitDepth, int channels, AudioEncoding encoding) {

    /** Standard 16 kHz Mono Linear PCM (16-bit signed, 32,000 bytes/sec). */
    public static final AudioFormat PCM_16KHZ_MONO =
            new AudioFormat(16000, 16, 1, AudioEncoding.LINEAR_PCM);

    /** Legacy narrow-band 8 kHz Mono Linear PCM (16-bit signed, 16,000 bytes/sec). */
    public static final AudioFormat PCM_8KHZ_MONO =
            new AudioFormat(8000, 16, 1, AudioEncoding.LINEAR_PCM);

    public AudioFormat {
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be positive: " + sampleRateHz);
        }
        if (bitDepth != 16) {
            throw new IllegalArgumentException("bitDepth must be 16: " + bitDepth);
        }
        if (channels != 1) {
            throw new IllegalArgumentException("channels must be 1 (mono): " + channels);
        }
        Objects.requireNonNull(encoding, "encoding cannot be null");
    }

    /** Returns bytes per audio sample (e.g., 2 bytes for 16-bit mono). */
    public int bytesPerSample() {
        return (bitDepth / 8) * channels;
    }

    /** Returns the required byte throughput per second (e.g., 32,000 bytes/sec at 16 kHz mono). */
    public int bytesPerSecond() {
        return sampleRateHz * bytesPerSample();
    }

    /** Returns expected byte length for a chunk of the specified duration in milliseconds. */
    public int frameSizeBytes(int durationMs) {
        return (sampleRateHz * durationMs / 1000) * bytesPerSample();
    }
}
