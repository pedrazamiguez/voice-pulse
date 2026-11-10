package com.voicepulse.conversation.domain.vad;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Voice Activity Detection Performance Benchmark")
class VadPerformanceBenchmarkTest {

    private static final int WARMUP_FRAMES = 1000;
    private static final int BENCHMARK_FRAMES = 5000;
    private static final double MAX_PERMISSIBLE_LATENCY_MS = 1.0;

    @Test
    @DisplayName("Asserts sub-1ms DSP and sliding window processing time per 20ms audio frame")
    void processingLatencyMustBeSubMillisecond() {
        VoiceActivityDetector detector = new VoiceActivityDetector();
        TrailingSilenceEvaluator evaluator = new TrailingSilenceEvaluator(detector);

        byte[] silence = SyntheticAudioTestFactory.generateSilenceFrame();
        byte[] speech = SyntheticAudioTestFactory.generateSpeechBurstFrame((short) 4000);

        // 1. Warm-up phase to engage JIT compilation
        for (int i = 0; i < WARMUP_FRAMES; i++) {
            byte[] frame = (i % 4 == 0) ? speech : silence;
            evaluator.evaluate(frame);
        }
        evaluator.reset();

        // 2. Timed benchmark phase
        long startNano = System.nanoTime();
        for (int i = 0; i < BENCHMARK_FRAMES; i++) {
            byte[] frame = (i % 5 == 0) ? speech : silence;
            VadEvaluationResult result = evaluator.evaluate(frame);
            if (result.turnBoundaryDetected()) {
                evaluator.reset();
            }
        }
        long totalElapsedNano = System.nanoTime() - startNano;

        double meanLatencyNano = (double) totalElapsedNano / BENCHMARK_FRAMES;
        double meanLatencyMs = meanLatencyNano / 1_000_000.0;

        // Telephony SLA Guard: 20ms frame must process in < 1.0 ms (target < 0.02 ms)
        assertThat(meanLatencyMs)
                .as("Mean VAD frame processing latency must be strictly sub-1ms")
                .isLessThan(MAX_PERMISSIBLE_LATENCY_MS);
    }
}
