package com.voicepulse.conversation.domain.vad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.instancio.Instancio;
import org.instancio.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("VadThresholdConfig Domain Contract Tests")
class VadThresholdConfigTest {

    @Nested
    @DisplayName("Default Configuration Invariants")
    class DefaultConfigTests {

        @Test
        @DisplayName("defaultConfig() initializes with RFC-compliant values")
        void defaultConfigShouldMatchRfcSpecifications() {
            VadThresholdConfig config = VadThresholdConfig.defaultConfig();

            assertThat(config.energyThreshold()).isEqualTo(500.0);
            assertThat(config.silenceFloor()).isEqualTo(100.0);
            assertThat(config.speechCeiling()).isEqualTo(3000.0);
            assertThat(config.minSpeechFrames()).isEqualTo(2);
            assertThat(config.silenceThresholdMs()).isEqualTo(500L);
            assertThat(config.frameDurationMs()).isEqualTo(20L);
        }
    }

    @Nested
    @DisplayName("Parameter Validation & Boundary Defense")
    class ParameterValidationTests {

        @Test
        @DisplayName("Valid custom values instantiate cleanly")
        void validCustomValuesShouldInstantiate() {
            VadThresholdConfig config = new VadThresholdConfig(400.0, 50.0, 2500.0, 3, 600L, 10L);

            assertThat(config.energyThreshold()).isEqualTo(400.0);
            assertThat(config.silenceFloor()).isEqualTo(50.0);
            assertThat(config.speechCeiling()).isEqualTo(2500.0);
            assertThat(config.minSpeechFrames()).isEqualTo(3);
            assertThat(config.silenceThresholdMs()).isEqualTo(600L);
            assertThat(config.frameDurationMs()).isEqualTo(10L);
        }

        @Test
        @DisplayName("Rejects non-positive energyThreshold")
        void rejectsNonPositiveEnergyThreshold() {
            assertThatThrownBy(() -> new VadThresholdConfig(0.0, 100.0, 3000.0, 2, 500L, 20L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("energyThreshold must be positive");

            assertThatThrownBy(() -> new VadThresholdConfig(-10.0, 100.0, 3000.0, 2, 500L, 20L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("energyThreshold must be positive");
        }

        @Test
        @DisplayName("Rejects negative silenceFloor")
        void rejectsNegativeSilenceFloor() {
            assertThatThrownBy(() -> new VadThresholdConfig(500.0, -1.0, 3000.0, 2, 500L, 20L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("silenceFloor cannot be negative");
        }

        @Test
        @DisplayName("Rejects speechCeiling less than or equal to silenceFloor")
        void rejectsInvalidSpeechCeiling() {
            assertThatThrownBy(() -> new VadThresholdConfig(500.0, 100.0, 100.0, 2, 500L, 20L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("speechCeiling must be greater than silenceFloor");

            assertThatThrownBy(() -> new VadThresholdConfig(500.0, 200.0, 100.0, 2, 500L, 20L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("speechCeiling must be greater than silenceFloor");
        }

        @Test
        @DisplayName("Rejects minSpeechFrames less than 1")
        void rejectsInvalidMinSpeechFrames() {
            assertThatThrownBy(() -> new VadThresholdConfig(500.0, 100.0, 3000.0, 0, 500L, 20L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("minSpeechFrames must be >= 1");
        }

        @Test
        @DisplayName("Rejects non-positive silenceThresholdMs")
        void rejectsNonPositiveSilenceThresholdMs() {
            assertThatThrownBy(() -> new VadThresholdConfig(500.0, 100.0, 3000.0, 2, 0L, 20L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("silenceThresholdMs must be positive");

            assertThatThrownBy(() -> new VadThresholdConfig(500.0, 100.0, 3000.0, 2, -100L, 20L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("silenceThresholdMs must be positive");
        }

        @Test
        @DisplayName("Rejects non-positive frameDurationMs")
        void rejectsNonPositiveFrameDurationMs() {
            assertThatThrownBy(() -> new VadThresholdConfig(500.0, 100.0, 3000.0, 2, 500L, 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("frameDurationMs must be positive");
        }

        @Test
        @DisplayName("Instancio fixture generation with valid bounds")
        void instancioGenerationWithValidBounds() {
            VadThresholdConfig config =
                    Instancio.of(VadThresholdConfig.class)
                            .set(Select.field(VadThresholdConfig::energyThreshold), 600.0)
                            .set(Select.field(VadThresholdConfig::silenceFloor), 50.0)
                            .set(Select.field(VadThresholdConfig::speechCeiling), 2000.0)
                            .set(Select.field(VadThresholdConfig::minSpeechFrames), 2)
                            .set(Select.field(VadThresholdConfig::silenceThresholdMs), 500L)
                            .set(Select.field(VadThresholdConfig::frameDurationMs), 20L)
                            .create();

            assertThat(config).isNotNull();
            assertThat(config.energyThreshold()).isEqualTo(600.0);
        }
    }
}
