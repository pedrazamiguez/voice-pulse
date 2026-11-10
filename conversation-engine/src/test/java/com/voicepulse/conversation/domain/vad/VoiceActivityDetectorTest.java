package com.voicepulse.conversation.domain.vad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("VoiceActivityDetector Domain Unit Tests")
class VoiceActivityDetectorTest {

    private VoiceActivityDetector detector;
    private VadThresholdConfig config;

    @BeforeEach
    void setUp() {
        config = VadThresholdConfig.defaultConfig();
        detector = new VoiceActivityDetector(config);
    }

    @Nested
    @DisplayName("Root Mean Square (RMS) Calculation")
    class RmsCalculationTests {

        @Test
        @DisplayName("Calculates zero RMS on pure digital silence")
        void pureSilenceShouldYieldZeroRms() {
            byte[] silence = SyntheticAudioTestFactory.generateSilenceFrame();

            double rms = detector.calculateRms(silence);

            assertThat(rms).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Calculates exact RMS for constant DC offset")
        void constantDcOffsetShouldYieldExactRms() {
            short constantValue = 1200;
            byte[] frame = new byte[SyntheticAudioTestFactory.DEFAULT_FRAME_BYTES];
            ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < SyntheticAudioTestFactory.DEFAULT_FRAME_SAMPLES; i++) {
                buf.putShort(constantValue);
            }

            double rms = detector.calculateRms(frame);

            assertThat(rms).isCloseTo(1200.0, within(0.001));
        }

        @Test
        @DisplayName("Calculates theoretical RMS for pure sine wave (A / sqrt(2))")
        void sineWaveShouldYieldTheoreticalRms() {
            short amplitude = 10000;
            byte[] sineWave = SyntheticAudioTestFactory.generateSineWaveFrame(440.0, amplitude);

            double rms = detector.calculateRms(sineWave);

            double expectedRms = amplitude / Math.sqrt(2.0); // ~7071.07
            assertThat(rms).isCloseTo(expectedRms, within(25.0));
        }

        @Test
        @DisplayName("Handles maximum 16-bit signed extremes without overflow")
        void signedExtremesShouldNotOverflow() {
            byte[] frame = new byte[SyntheticAudioTestFactory.DEFAULT_FRAME_BYTES];
            ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < SyntheticAudioTestFactory.DEFAULT_FRAME_SAMPLES; i++) {
                buf.putShort(i % 2 == 0 ? Short.MAX_VALUE : Short.MIN_VALUE);
            }

            double rms = detector.calculateRms(frame);

            assertThat(rms).isGreaterThan(32760.0);
            assertThat(rms).isLessThanOrEqualTo(32768.0);
        }

        @Test
        @DisplayName("Rejects null, empty, or misaligned byte arrays")
        void rejectsInvalidByteArrays() {
            assertThatThrownBy(() -> detector.calculateRms(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("pcmData cannot be null");

            assertThatThrownBy(() -> detector.calculateRms(new byte[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pcmData must be non-empty");

            assertThatThrownBy(() -> detector.calculateRms(new byte[5]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2-byte aligned");
        }
    }

    @Nested
    @DisplayName("Speech Probability Calculation")
    class SpeechProbabilityTests {

        @Test
        @DisplayName("Returns 0.0 for energy at or below silence floor")
        void returnsZeroBelowSilenceFloor() {
            assertThat(detector.calculateSpeechProbability(0.0)).isEqualTo(0.0);
            assertThat(detector.calculateSpeechProbability(50.0)).isEqualTo(0.0);
            assertThat(detector.calculateSpeechProbability(100.0)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Returns 1.0 for energy at or above speech ceiling")
        void returnsOneAboveSpeechCeiling() {
            assertThat(detector.calculateSpeechProbability(3000.0)).isEqualTo(1.0);
            assertThat(detector.calculateSpeechProbability(5000.0)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Linearly interpolates probability between silence floor and speech ceiling")
        void interpolatesProbabilityWithinBounds() {
            double midEnergy = (config.silenceFloor() + config.speechCeiling()) / 2.0; // 1550.0

            double probability = detector.calculateSpeechProbability(midEnergy);

            assertThat(probability).isCloseTo(0.5, within(0.001));
        }

        @Test
        @DisplayName("Guarantees monotonic increase of probability with energy")
        void probabilityIsMonotonicallyIncreasing() {
            double p1 = detector.calculateSpeechProbability(200.0);
            double p2 = detector.calculateSpeechProbability(500.0);
            double p3 = detector.calculateSpeechProbability(1500.0);
            double p4 = detector.calculateSpeechProbability(2800.0);

            assertThat(p1).isLessThan(p2);
            assertThat(p2).isLessThan(p3);
            assertThat(p3).isLessThan(p4);
        }
    }

    @Nested
    @DisplayName("Speech Threshold Detection")
    class SpeechDetectionTests {

        @Test
        @DisplayName("Identifies silence when energy is below threshold")
        void silenceBelowThreshold() {
            byte[] silence = SyntheticAudioTestFactory.generateSilenceFrame();

            assertThat(detector.isSpeech(silence)).isFalse();
            assertThat(detector.isSpeech(150.0)).isFalse();
            assertThat(detector.isSpeech(499.9)).isFalse();
        }

        @Test
        @DisplayName("Identifies speech when energy meets or exceeds threshold")
        void speechAtOrAboveThreshold() {
            byte[] speechBurst = SyntheticAudioTestFactory.generateSpeechBurstFrame((short) 4000);

            assertThat(detector.isSpeech(speechBurst)).isTrue();
            assertThat(detector.isSpeech(500.0)).isTrue();
            assertThat(detector.isSpeech(1200.0)).isTrue();
        }

        @Test
        @DisplayName("Low amplitude white noise is classified as silence")
        void lowAmplitudeNoiseIsSilence() {
            byte[] lowNoise = SyntheticAudioTestFactory.generateWhiteNoiseFrame((short) 60, 42L);

            double rms = detector.calculateRms(lowNoise);
            assertThat(rms).isLessThan(config.energyThreshold());
            assertThat(detector.isSpeech(lowNoise)).isFalse();
        }
    }

    @Nested
    @DisplayName("Single Frame Evaluation")
    class SingleFrameEvaluationTests {

        @Test
        @DisplayName("evaluateFrame produces consistent stateless VadEvaluationResult")
        void evaluateFrameShouldProduceConsistentResult() {
            byte[] speechFrame = SyntheticAudioTestFactory.generateSpeechBurstFrame((short) 5000);

            VadEvaluationResult result = detector.evaluateFrame(speechFrame);

            assertThat(result.rmsEnergy()).isGreaterThan(config.energyThreshold());
            assertThat(result.speechProbability()).isGreaterThan(0.5);
            assertThat(result.speechDetected()).isTrue();
            assertThat(result.contiguousSilenceMs()).isEqualTo(0L);
            assertThat(result.turnBoundaryDetected()).isFalse();
        }
    }
}
