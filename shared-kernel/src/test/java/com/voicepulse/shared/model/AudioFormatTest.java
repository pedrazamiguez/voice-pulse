package com.voicepulse.shared.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AudioFormatTest {

    @Test
    @DisplayName("standard 16 kHz mono format has correct byte rate and calculations")
    void standard16kHzMonoCalculations() {
        AudioFormat format = AudioFormat.PCM_16KHZ_MONO;

        assertThat(format.sampleRateHz()).isEqualTo(16000);
        assertThat(format.bitDepth()).isEqualTo(16);
        assertThat(format.channels()).isEqualTo(1);
        assertThat(format.encoding()).isEqualTo(AudioEncoding.LINEAR_PCM);

        assertThat(format.bytesPerSample()).isEqualTo(2);
        assertThat(format.bytesPerSecond()).isEqualTo(32000);
        // 20ms at 16 kHz = 320 samples * 2 bytes = 640 bytes
        assertThat(format.frameSizeBytes(20)).isEqualTo(640);
    }

    @Test
    @DisplayName("standard 8 kHz mono format has correct calculations")
    void standard8kHzMonoCalculations() {
        AudioFormat format = AudioFormat.PCM_8KHZ_MONO;

        assertThat(format.sampleRateHz()).isEqualTo(8000);
        assertThat(format.bitDepth()).isEqualTo(16);
        assertThat(format.channels()).isEqualTo(1);
        assertThat(format.encoding()).isEqualTo(AudioEncoding.LINEAR_PCM);

        assertThat(format.bytesPerSample()).isEqualTo(2);
        assertThat(format.bytesPerSecond()).isEqualTo(16000);
        // 20ms at 8 kHz = 160 samples * 2 bytes = 320 bytes
        assertThat(format.frameSizeBytes(20)).isEqualTo(320);
    }

    @Test
    @DisplayName("rejects non-positive sample rates")
    void nonPositiveSampleRateThrows() {
        assertThatThrownBy(() -> new AudioFormat(0, 16, 1, AudioEncoding.LINEAR_PCM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRateHz must be positive");

        assertThatThrownBy(() -> new AudioFormat(-16000, 16, 1, AudioEncoding.LINEAR_PCM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRateHz must be positive");
    }

    @Test
    @DisplayName("rejects non-16 bit depth")
    void invalidBitDepthThrows() {
        assertThatThrownBy(() -> new AudioFormat(16000, 8, 1, AudioEncoding.LINEAR_PCM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bitDepth must be 16");

        assertThatThrownBy(() -> new AudioFormat(16000, 24, 1, AudioEncoding.LINEAR_PCM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bitDepth must be 16");
    }

    @Test
    @DisplayName("rejects multi-channel audio")
    void multiChannelThrows() {
        assertThatThrownBy(() -> new AudioFormat(16000, 16, 2, AudioEncoding.LINEAR_PCM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels must be 1 (mono)");
    }

    @Test
    @DisplayName("rejects null encoding")
    void nullEncodingThrows() {
        assertThatThrownBy(() -> new AudioFormat(16000, 16, 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("encoding cannot be null");
    }

    @Test
    @DisplayName("AudioEncoding resolves stable wireId bidirectionally")
    void audioEncodingWireIdResolves() {
        assertThat(AudioEncoding.LINEAR_PCM.wireId()).isZero();
        assertThat(AudioEncoding.fromWireId((byte) 0)).isEqualTo(AudioEncoding.LINEAR_PCM);

        assertThatThrownBy(() -> AudioEncoding.fromWireId((byte) 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown AudioEncoding wireId: 99");
    }
}
