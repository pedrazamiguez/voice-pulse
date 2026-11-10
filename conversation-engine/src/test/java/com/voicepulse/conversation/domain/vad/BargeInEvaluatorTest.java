package com.voicepulse.conversation.domain.vad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BargeInEvaluator Pure Domain Tests")
class BargeInEvaluatorTest {

    private BargeInEvaluator evaluator;
    private VoiceActivityDetector vad;

    @BeforeEach
    void setUp() {
        vad = new VoiceActivityDetector();
        evaluator = new BargeInEvaluator(vad);
    }

    @Nested
    @DisplayName("Constructor and Validation")
    class ConstructorValidation {

        @Test
        @DisplayName("default constructor initializes with standard VAD")
        void defaultConstructor() {
            BargeInEvaluator defaultEvaluator = new BargeInEvaluator();
            byte[] silence = SyntheticAudioTestFactory.generateSilenceFrame();

            BargeInEvaluationResult result =
                    defaultEvaluator.evaluate(silence, TurnPhase.BOT_SPEAKING);

            assertThat(result.bargeInDetected()).isFalse();
        }

        @Test
        @DisplayName("null VAD rejected")
        void nullVadRejected() {
            assertThatThrownBy(() -> new BargeInEvaluator(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("vad cannot be null");
        }

        @Test
        @DisplayName("null PCM data rejected")
        void nullPcmRejected() {
            assertThatThrownBy(() -> evaluator.evaluate(null, true))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("pcmData cannot be null");
        }

        @Test
        @DisplayName("null TurnPhase rejected")
        void nullTurnPhaseRejected() {
            byte[] silence = SyntheticAudioTestFactory.generateSilenceFrame();
            assertThatThrownBy(() -> evaluator.evaluate(silence, (TurnPhase) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("turnPhase cannot be null");
        }
    }

    @Nested
    @DisplayName("Barge-In Evaluation Logic")
    class EvaluationLogic {

        @Test
        @DisplayName("speech detected during active bot speech triggers barge-in")
        void speechDuringBotPlaybackTriggersBargeIn() {
            // High energy tone representing loud caller utterance (1000 Hz at amplitude 8000)
            byte[] speechFrame =
                    SyntheticAudioTestFactory.generateSineWaveFrame(1000.0, (short) 8000);

            BargeInEvaluationResult result =
                    evaluator.evaluate(speechFrame, TurnPhase.BOT_SPEAKING);

            assertThat(result.bargeInDetected()).isTrue();
            assertThat(result.rmsEnergy()).isGreaterThan(500.0);
            assertThat(result.speechProbability()).isGreaterThan(0.0);
            assertThat(evaluator.isBargeIn(speechFrame, TurnPhase.BOT_SPEAKING)).isTrue();
        }

        @Test
        @DisplayName("speech detected during LISTENING phase does NOT trigger barge-in")
        void speechDuringListeningDoesNotTriggerBargeIn() {
            byte[] speechFrame =
                    SyntheticAudioTestFactory.generateSineWaveFrame(1000.0, (short) 8000);

            BargeInEvaluationResult result = evaluator.evaluate(speechFrame, TurnPhase.LISTENING);

            assertThat(result.bargeInDetected()).isFalse();
            assertThat(result.rmsEnergy()).isGreaterThan(500.0);
            assertThat(evaluator.isBargeIn(speechFrame, TurnPhase.LISTENING)).isFalse();
        }

        @Test
        @DisplayName("silence during active bot speech does NOT trigger barge-in")
        void silenceDuringBotPlaybackDoesNotTriggerBargeIn() {
            byte[] silenceFrame = SyntheticAudioTestFactory.generateSilenceFrame();

            BargeInEvaluationResult result =
                    evaluator.evaluate(silenceFrame, TurnPhase.BOT_SPEAKING);

            assertThat(result.bargeInDetected()).isFalse();
            assertThat(result.rmsEnergy()).isZero();
            assertThat(result.speechProbability()).isZero();
            assertThat(evaluator.isBargeIn(silenceFrame, TurnPhase.BOT_SPEAKING)).isFalse();
        }

        @Test
        @DisplayName("evaluateVad evaluates pre-computed VAD result with turn phase")
        void evaluateVadPrecomputed() {
            VadEvaluationResult speechVad = new VadEvaluationResult(1200.0, 0.95, true, 0L, false);
            VadEvaluationResult silenceVad = new VadEvaluationResult(20.0, 0.0, false, 200L, false);

            BargeInEvaluationResult botSpeechResult =
                    evaluator.evaluateVad(speechVad, TurnPhase.BOT_SPEAKING);
            BargeInEvaluationResult listeningResult =
                    evaluator.evaluateVad(speechVad, TurnPhase.LISTENING);
            BargeInEvaluationResult botSilenceResult =
                    evaluator.evaluateVad(silenceVad, TurnPhase.BOT_SPEAKING);

            assertThat(botSpeechResult.bargeInDetected()).isTrue();
            assertThat(listeningResult.bargeInDetected()).isFalse();
            assertThat(botSilenceResult.bargeInDetected()).isFalse();
        }
    }

    @Nested
    @DisplayName("Performance Benchmark")
    class PerformanceBenchmark {

        @Test
        @DisplayName(
                "single frame barge-in evaluation completes in sub-millisecond latency (< 1ms)")
        void subMillisecondEvaluationLatency() {
            byte[] speechFrame =
                    SyntheticAudioTestFactory.generateSineWaveFrame(800.0, (short) 6000);

            // Warmup
            for (int i = 0; i < 1000; i++) {
                evaluator.evaluate(speechFrame, TurnPhase.BOT_SPEAKING);
            }

            long startNano = System.nanoTime();
            int iterations = 10000;
            for (int i = 0; i < iterations; i++) {
                evaluator.evaluate(speechFrame, TurnPhase.BOT_SPEAKING);
            }
            long elapsedNano = System.nanoTime() - startNano;
            double avgMicroseconds = (elapsedNano / (double) iterations) / 1000.0;

            // Must execute well within 1000 microseconds (1 ms)
            assertThat(avgMicroseconds).isLessThan(100.0);
        }
    }
}
