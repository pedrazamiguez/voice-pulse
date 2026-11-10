package com.voicepulse.conversation.domain.vad;

import java.util.Objects;

/**
 * Pure domain Digital Signal Processing (DSP) service computing Root Mean Square (RMS) energy and
 * speech probability on signed 16-bit Little-Endian Linear PCM frames.
 *
 * <p>Optimized for zero heap allocation in inner loops to eliminate GC pressure in real-time
 * telephony pipelines.
 */
public class VoiceActivityDetector {

    private final VadThresholdConfig config;

    /** Constructs detector with default telephony configuration. */
    public VoiceActivityDetector() {
        this(VadThresholdConfig.defaultConfig());
    }

    /**
     * Constructs detector with custom threshold configuration.
     *
     * @param config parameter configuration
     */
    public VoiceActivityDetector(VadThresholdConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * Computes Root Mean Square (RMS) energy over signed 16-bit Little-Endian PCM byte buffer.
     *
     * <p>Executes strictly via primitive bitwise operations with zero object allocations.
     *
     * @param pcmData raw signed 16-bit LE PCM byte array
     * @return RMS energy value in range [0.0, 32768.0]
     * @throws NullPointerException if pcmData is null
     * @throws IllegalArgumentException if pcmData is empty or length is not divisible by 2
     */
    public double calculateRms(byte[] pcmData) {
        Objects.requireNonNull(pcmData, "pcmData cannot be null");
        if (pcmData.length == 0) {
            throw new IllegalArgumentException("pcmData must be non-empty");
        }
        if (pcmData.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "pcmData length must be 2-byte aligned, but was: " + pcmData.length);
        }

        int sampleCount = pcmData.length / 2;
        long sumSquares = 0L;

        for (int i = 0; i < pcmData.length; i += 2) {
            short sample = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));
            sumSquares += (long) sample * sample;
        }

        double meanSquare = (double) sumSquares / sampleCount;
        return Math.sqrt(meanSquare);
    }

    /**
     * Computes continuous speech probability in range [0.0, 1.0] based on RMS energy.
     *
     * @param rmsEnergy frame RMS energy
     * @return normalized probability in [0.0, 1.0]
     */
    public double calculateSpeechProbability(double rmsEnergy) {
        if (rmsEnergy <= config.silenceFloor()) {
            return 0.0;
        }
        if (rmsEnergy >= config.speechCeiling()) {
            return 1.0;
        }
        double normalized =
                (rmsEnergy - config.silenceFloor())
                        / (config.speechCeiling() - config.silenceFloor());
        return Math.clamp(normalized, 0.0, 1.0);
    }

    /**
     * Determines whether RMS energy meets or exceeds the speech threshold.
     *
     * @param rmsEnergy frame RMS energy
     * @return true if energy indicates active speech
     */
    public boolean isSpeech(double rmsEnergy) {
        return rmsEnergy >= config.energyThreshold();
    }

    /**
     * Determines whether PCM byte buffer indicates active speech.
     *
     * @param pcmData raw signed 16-bit LE PCM byte array
     * @return true if frame energy indicates active speech
     */
    public boolean isSpeech(byte[] pcmData) {
        return isSpeech(calculateRms(pcmData));
    }

    /**
     * Evaluates a single frame independently without temporal state history.
     *
     * @param pcmData raw signed 16-bit LE PCM byte array
     * @return stateless evaluation result
     */
    public VadEvaluationResult evaluateFrame(byte[] pcmData) {
        double rms = calculateRms(pcmData);
        double probability = calculateSpeechProbability(rms);
        boolean speech = isSpeech(rms);
        return new VadEvaluationResult(rms, probability, speech, 0L, false);
    }

    public VadThresholdConfig config() {
        return this.config;
    }
}
