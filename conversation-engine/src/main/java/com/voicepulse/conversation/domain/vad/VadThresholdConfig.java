package com.voicepulse.conversation.domain.vad;

/**
 * Immutable domain configuration record defining energy thresholds, speech onset minimums, and
 * silence boundaries for real-time Voice Activity Detection.
 *
 * <p>Enforces telephony standards specified in RFC-0001 §4.3 and RFC-0003 §3 with fail-fast
 * boundary verification.
 */
public record VadThresholdConfig(
        double energyThreshold,
        double silenceFloor,
        double speechCeiling,
        int minSpeechFrames,
        long silenceThresholdMs,
        long frameDurationMs) {

    /** Returns standard default configuration aligned with RFC telephony invariants. */
    public static VadThresholdConfig defaultConfig() {
        return new VadThresholdConfig(500.0, 100.0, 3000.0, 2, 500L, 20L);
    }

    public VadThresholdConfig {
        if (energyThreshold <= 0.0) {
            throw new IllegalArgumentException(
                    "energyThreshold must be positive: " + energyThreshold);
        }
        if (silenceFloor < 0.0) {
            throw new IllegalArgumentException("silenceFloor cannot be negative: " + silenceFloor);
        }
        if (speechCeiling <= silenceFloor) {
            throw new IllegalArgumentException(
                    "speechCeiling must be greater than silenceFloor: " + speechCeiling);
        }
        if (minSpeechFrames < 1) {
            throw new IllegalArgumentException("minSpeechFrames must be >= 1: " + minSpeechFrames);
        }
        if (silenceThresholdMs <= 0L) {
            throw new IllegalArgumentException(
                    "silenceThresholdMs must be positive: " + silenceThresholdMs);
        }
        if (frameDurationMs <= 0L) {
            throw new IllegalArgumentException(
                    "frameDurationMs must be positive: " + frameDurationMs);
        }
    }
}
