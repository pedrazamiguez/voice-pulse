package com.voicepulse.conversation.domain.vad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TrailingSilenceEvaluator Domain Unit Tests")
class TrailingSilenceEvaluatorTest {

    private TrailingSilenceEvaluator evaluator;
    private byte[] silenceFrame;
    private byte[] speechFrame;

    @BeforeEach
    void setUp() {
        evaluator = new TrailingSilenceEvaluator();
        silenceFrame = SyntheticAudioTestFactory.generateSilenceFrame();
        speechFrame = SyntheticAudioTestFactory.generateSpeechBurstFrame((short) 4500);
    }

    @Nested
    @DisplayName("Initial State & Pre-Speech Silence Invariants")
    class InitialSilenceTests {

        @Test
        @DisplayName("Starts in AwaitingSpeech(0) state")
        void initialAwaitingSpeechState() {
            assertThat(evaluator.state()).isInstanceOf(TrailingSilenceState.AwaitingSpeech.class);
            TrailingSilenceState.AwaitingSpeech awaiting =
                    (TrailingSilenceState.AwaitingSpeech) evaluator.state();
            assertThat(awaiting.speechFramesObserved()).isEqualTo(0);
            assertThat(evaluator.isTurnComplete()).isFalse();
        }

        @Test
        @DisplayName("Initial contiguous silence does NOT trigger turn completion")
        void preSpeechSilenceDoesNotCompleteTurn() {
            // Stream 50 silence frames (1000 ms) before caller speaks
            for (int i = 0; i < 50; i++) {
                VadEvaluationResult result = evaluator.evaluate(silenceFrame);
                assertThat(result.speechDetected()).isFalse();
                assertThat(result.turnBoundaryDetected()).isFalse();
                assertThat(evaluator.isTurnComplete()).isFalse();
            }

            assertThat(evaluator.state()).isInstanceOf(TrailingSilenceState.AwaitingSpeech.class);
        }
    }

    @Nested
    @DisplayName("Acoustic Transient Filtering & Speech Onset")
    class SpeechOnsetTests {

        @Test
        @DisplayName("Suppresses transient pop (1 speech frame < minSpeechFrames)")
        void suppressesTransientPop() {
            // 1 transient speech frame
            VadEvaluationResult burstResult = evaluator.evaluate(speechFrame);
            assertThat(burstResult.speechDetected()).isTrue();
            assertThat(burstResult.turnBoundaryDetected()).isFalse();
            assertThat(evaluator.state()).isInstanceOf(TrailingSilenceState.AwaitingSpeech.class);

            // Followed by silence -> resets back to AwaitingSpeech(0)
            VadEvaluationResult silenceResult = evaluator.evaluate(silenceFrame);
            assertThat(silenceResult.speechDetected()).isFalse();
            assertThat(silenceResult.turnBoundaryDetected()).isFalse();
            TrailingSilenceState.AwaitingSpeech awaiting =
                    (TrailingSilenceState.AwaitingSpeech) evaluator.state();
            assertThat(awaiting.speechFramesObserved()).isEqualTo(0);
        }

        @Test
        @DisplayName("Transitions to InSpeech after minSpeechFrames (2 frames)")
        void transitionsToInSpeechOnOnset() {
            evaluator.evaluate(speechFrame);
            assertThat(evaluator.state()).isInstanceOf(TrailingSilenceState.AwaitingSpeech.class);

            VadEvaluationResult secondResult = evaluator.evaluate(speechFrame);
            assertThat(secondResult.speechDetected()).isTrue();
            assertThat(secondResult.turnBoundaryDetected()).isFalse();
            assertThat(evaluator.state()).isInstanceOf(TrailingSilenceState.InSpeech.class);
            TrailingSilenceState.InSpeech inSpeech =
                    (TrailingSilenceState.InSpeech) evaluator.state();
            assertThat(inSpeech.trailingSilenceMs()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("Conversational Turns & Trailing Silence Boundary")
    class ConversationalTurnTests {

        @Test
        @DisplayName(
                "Intra-turn pause (< 500ms) accumulates silence and resets on subsequent speech")
        void intraTurnPauseResetsOnSubsequentSpeech() {
            // Speech onset (2 frames)
            evaluator.evaluate(speechFrame);
            evaluator.evaluate(speechFrame);
            assertThat(evaluator.state()).isInstanceOf(TrailingSilenceState.InSpeech.class);

            // Caller pauses for 200 ms (10 silent frames of 20 ms)
            for (int i = 1; i <= 10; i++) {
                VadEvaluationResult pauseResult = evaluator.evaluate(silenceFrame);
                assertThat(pauseResult.speechDetected()).isFalse();
                assertThat(pauseResult.turnBoundaryDetected()).isFalse();
                assertThat(pauseResult.contiguousSilenceMs()).isEqualTo(i * 20L);
            }

            TrailingSilenceState.InSpeech pausedState =
                    (TrailingSilenceState.InSpeech) evaluator.state();
            assertThat(pausedState.trailingSilenceMs()).isEqualTo(200L);

            // Caller resumes speaking -> silence counter resets to 0
            VadEvaluationResult resumeResult = evaluator.evaluate(speechFrame);
            assertThat(resumeResult.speechDetected()).isTrue();
            assertThat(resumeResult.contiguousSilenceMs()).isEqualTo(0L);
            assertThat(resumeResult.turnBoundaryDetected()).isFalse();

            TrailingSilenceState.InSpeech resumedState =
                    (TrailingSilenceState.InSpeech) evaluator.state();
            assertThat(resumedState.trailingSilenceMs()).isEqualTo(0L);
        }

        @Test
        @DisplayName("500 ms continuous silence triggers turn boundary completion")
        void fiveHundredMsContinuousSilenceTriggersTurnComplete() {
            // Speech onset (2 frames)
            evaluator.evaluate(speechFrame);
            evaluator.evaluate(speechFrame);

            // Caller finishes speaking: 24 frames of silence (480 ms) -> boundary not reached
            for (int i = 1; i <= 24; i++) {
                VadEvaluationResult result = evaluator.evaluate(silenceFrame);
                assertThat(result.turnBoundaryDetected()).isFalse();
                assertThat(evaluator.isTurnComplete()).isFalse();
                assertThat(result.contiguousSilenceMs()).isEqualTo(i * 20L);
            }

            // Frame 25: 500 ms silence reached!
            VadEvaluationResult boundaryResult = evaluator.evaluate(silenceFrame);
            assertThat(boundaryResult.turnBoundaryDetected()).isTrue();
            assertThat(boundaryResult.contiguousSilenceMs()).isEqualTo(500L);
            assertThat(evaluator.isTurnComplete()).isTrue();
            assertThat(evaluator.state()).isInstanceOf(TrailingSilenceState.TurnComplete.class);

            // Additional silence maintains turn complete status
            VadEvaluationResult postResult = evaluator.evaluate(silenceFrame);
            assertThat(postResult.turnBoundaryDetected()).isTrue();
            assertThat(postResult.contiguousSilenceMs()).isEqualTo(520L);
            assertThat(evaluator.isTurnComplete()).isTrue();
        }

        @Test
        @DisplayName(
                "reset() returns evaluator to AwaitingSpeech for next turn while preserving"
                        + " completedTurnCount")
        void resetReArmsEvaluator() {
            assertThat(evaluator.completedTurnCount()).isZero();

            // Trigger first turn complete
            evaluator.evaluate(speechFrame);
            evaluator.evaluate(speechFrame);
            for (int i = 0; i < 25; i++) {
                evaluator.evaluate(silenceFrame);
            }
            assertThat(evaluator.isTurnComplete()).isTrue();
            assertThat(evaluator.completedTurnCount()).isEqualTo(1);

            // Reset
            evaluator.reset();

            assertThat(evaluator.isTurnComplete()).isFalse();
            assertThat(evaluator.completedTurnCount()).isEqualTo(1);
            assertThat(evaluator.state()).isInstanceOf(TrailingSilenceState.AwaitingSpeech.class);
            TrailingSilenceState.AwaitingSpeech awaiting =
                    (TrailingSilenceState.AwaitingSpeech) evaluator.state();
            assertThat(awaiting.speechFramesObserved()).isEqualTo(0);

            // Trigger second turn complete
            evaluator.evaluate(speechFrame);
            evaluator.evaluate(speechFrame);
            for (int i = 0; i < 25; i++) {
                evaluator.evaluate(silenceFrame);
            }
            assertThat(evaluator.isTurnComplete()).isTrue();
            assertThat(evaluator.completedTurnCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("AudioChunk Overload Integration")
    class AudioChunkIntegrationTests {

        @Test
        @DisplayName("evaluate(AudioChunk) delegates cleanly to PCM byte evaluator")
        void evaluatesAudioChunkEnvelopes() {
            CallSessionId sessionId = new CallSessionId(UUID.randomUUID());
            AudioChunk chunk1 =
                    SyntheticAudioTestFactory.wrapAsChunk(sessionId, 0, Instant.now(), speechFrame);
            AudioChunk chunk2 =
                    SyntheticAudioTestFactory.wrapAsChunk(sessionId, 1, Instant.now(), speechFrame);

            evaluator.evaluate(chunk1);
            VadEvaluationResult result = evaluator.evaluate(chunk2);

            assertThat(result.speechDetected()).isTrue();
            assertThat(evaluator.state()).isInstanceOf(TrailingSilenceState.InSpeech.class);
        }

        @Test
        @DisplayName("Rejects null AudioChunk")
        void rejectsNullAudioChunk() {
            assertThatThrownBy(() -> evaluator.evaluate((AudioChunk) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("chunk cannot be null");
        }
    }
}
