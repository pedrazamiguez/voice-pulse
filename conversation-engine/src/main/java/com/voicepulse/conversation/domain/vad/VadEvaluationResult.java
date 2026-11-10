package com.voicepulse.conversation.domain.vad;

/**
 * Immutable domain value record indicating frame energy, speech detected status, contiguous silence
 * duration, and turn boundary flag.
 */
public record VadEvaluationResult(
        double rmsEnergy,
        double speechProbability,
        boolean speechDetected,
        long contiguousSilenceMs,
        boolean turnBoundaryDetected) {

    public VadEvaluationResult {
        if (rmsEnergy < 0.0) {
            throw new IllegalArgumentException("rmsEnergy cannot be negative: " + rmsEnergy);
        }
        if (speechProbability < 0.0 || speechProbability > 1.0) {
            throw new IllegalArgumentException(
                    "speechProbability must be in [0.0, 1.0]: " + speechProbability);
        }
        if (contiguousSilenceMs < 0L) {
            throw new IllegalArgumentException(
                    "contiguousSilenceMs cannot be negative: " + contiguousSilenceMs);
        }
    }
}
