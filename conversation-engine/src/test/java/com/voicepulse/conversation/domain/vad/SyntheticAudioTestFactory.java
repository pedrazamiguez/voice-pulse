package com.voicepulse.conversation.domain.vad;

import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallSessionId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;

/**
 * Deterministic synthetic 16-bit Little-Endian Linear PCM audio frame generator for domain tests.
 *
 * <p>Produces pure silence, single-frequency sine waves, white noise, and synthetic voice bursts
 * aligned to 16 kHz mono telephony frames (20 ms = 320 samples / 640 bytes).
 */
public final class SyntheticAudioTestFactory {

    public static final int DEFAULT_SAMPLE_RATE_HZ = 16000;
    public static final int DEFAULT_FRAME_DURATION_MS = 20;
    public static final int DEFAULT_FRAME_SAMPLES =
            DEFAULT_SAMPLE_RATE_HZ * DEFAULT_FRAME_DURATION_MS / 1000; // 320
    public static final int DEFAULT_FRAME_BYTES = DEFAULT_FRAME_SAMPLES * 2; // 640

    private SyntheticAudioTestFactory() {
        // Utility class
    }

    /** Generates a 640-byte frame of pure digital silence (all zeros). */
    public static byte[] generateSilenceFrame() {
        return new byte[DEFAULT_FRAME_BYTES];
    }

    /** Generates an arbitrary number of silent samples. */
    public static byte[] generateSilence(int sampleCount) {
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("sampleCount must be positive: " + sampleCount);
        }
        return new byte[sampleCount * 2];
    }

    /**
     * Generates a single-frequency sine wave frame.
     *
     * @param frequencyHz wave frequency in Hertz (e.g. 440.0, 1000.0)
     * @param amplitude peak amplitude (1 to 32767)
     * @return 640-byte 16-bit LE PCM byte array
     */
    public static byte[] generateSineWaveFrame(double frequencyHz, short amplitude) {
        return generateSineWave(
                DEFAULT_FRAME_SAMPLES, DEFAULT_SAMPLE_RATE_HZ, frequencyHz, amplitude);
    }

    /**
     * Generates a single-frequency sine wave with specified sample count.
     *
     * @param sampleCount number of 16-bit samples
     * @param sampleRateHz sampling rate in Hz
     * @param frequencyHz frequency in Hz
     * @param amplitude peak amplitude
     * @return 16-bit LE PCM byte array
     */
    public static byte[] generateSineWave(
            int sampleCount, int sampleRateHz, double frequencyHz, short amplitude) {
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("sampleCount must be positive: " + sampleCount);
        }
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be positive: " + sampleRateHz);
        }
        if (frequencyHz <= 0.0) {
            throw new IllegalArgumentException("frequencyHz must be positive: " + frequencyHz);
        }

        byte[] bytes = new byte[sampleCount * 2];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        double angularFrequency = 2.0 * Math.PI * frequencyHz;
        for (int i = 0; i < sampleCount; i++) {
            double timeSec = (double) i / sampleRateHz;
            short sample = (short) Math.round(amplitude * Math.sin(angularFrequency * timeSec));
            buffer.putShort(sample);
        }

        return bytes;
    }

    /**
     * Generates a pseudorandom white noise frame with bounded amplitude.
     *
     * @param maxAmplitude maximum absolute amplitude
     * @param seed random seed for reproducibility
     * @return 640-byte 16-bit LE PCM byte array
     */
    public static byte[] generateWhiteNoiseFrame(short maxAmplitude, long seed) {
        return generateWhiteNoise(DEFAULT_FRAME_SAMPLES, maxAmplitude, seed);
    }

    /** Generates pseudorandom white noise with specified sample count. */
    public static byte[] generateWhiteNoise(int sampleCount, short maxAmplitude, long seed) {
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("sampleCount must be positive: " + sampleCount);
        }
        if (maxAmplitude <= 0) {
            throw new IllegalArgumentException("maxAmplitude must be positive: " + maxAmplitude);
        }

        Random random = new Random(seed);
        byte[] bytes = new byte[sampleCount * 2];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        int range = (maxAmplitude * 2) + 1;
        for (int i = 0; i < sampleCount; i++) {
            short sample = (short) (random.nextInt(range) - maxAmplitude);
            buffer.putShort(sample);
        }

        return bytes;
    }

    /**
     * Generates a multi-harmonic synthetic speech burst frame (fundamental 200 Hz + harmonics).
     *
     * @param peakAmplitude peak target amplitude
     * @return 640-byte 16-bit LE PCM byte array
     */
    public static byte[] generateSpeechBurstFrame(short peakAmplitude) {
        byte[] bytes = new byte[DEFAULT_FRAME_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        double f0 = 200.0;
        double f1 = 400.0;
        double f2 = 800.0;
        double f3 = 1200.0;

        for (int i = 0; i < DEFAULT_FRAME_SAMPLES; i++) {
            double t = (double) i / DEFAULT_SAMPLE_RATE_HZ;
            double wave =
                    (0.5 * Math.sin(2.0 * Math.PI * f0 * t))
                            + (0.3 * Math.sin(2.0 * Math.PI * f1 * t))
                            + (0.15 * Math.sin(2.0 * Math.PI * f2 * t))
                            + (0.05 * Math.sin(2.0 * Math.PI * f3 * t));
            short sample = (short) Math.round(peakAmplitude * wave);
            buffer.putShort(sample);
        }

        return bytes;
    }

    /** Wraps raw PCM bytes into a domain {@link AudioChunk}. */
    public static AudioChunk wrapAsChunk(
            CallSessionId sessionId, long sequenceNumber, Instant timestamp, byte[] pcmData) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(pcmData, "pcmData cannot be null");

        return new AudioChunk(
                sessionId, sequenceNumber, timestamp, pcmData, AudioFormat.PCM_16KHZ_MONO);
    }
}
