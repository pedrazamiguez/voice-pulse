package com.voicepulse.conversation.domain.vad;

/**
 * Immutable domain record indicating whether caller speech interrupted active bot playback,
 * alongside Root Mean Square (RMS) energy and speech probability metrics.
 */
public record BargeInEvaluationResult(
        boolean bargeInDetected, double rmsEnergy, double speechProbability) {
    public BargeInEvaluationResult {
        if (rmsEnergy < 0.0) {
            throw new IllegalArgumentException("rmsEnergy cannot be negative: " + rmsEnergy);
        }
        if (speechProbability < 0.0 || speechProbability > 1.0) {
            throw new IllegalArgumentException(
                    "speechProbability must be in [0.0, 1.0]: " + speechProbability);
        }
    }

    /** Creates a non-barge-in result with calculated acoustics. */
    public static BargeInEvaluationResult none(double rmsEnergy, double speechProbability) {
        return new BargeInEvaluationResult(false, rmsEnergy, speechProbability);
    }

    /** Creates a triggered barge-in interruption result with calculated acoustics. */
    public static BargeInEvaluationResult triggered(double rmsEnergy, double speechProbability) {
        return new BargeInEvaluationResult(true, rmsEnergy, speechProbability);
    }
}
