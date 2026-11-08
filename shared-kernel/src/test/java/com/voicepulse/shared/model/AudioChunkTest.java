package com.voicepulse.shared.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AudioChunkTest {

    private final CallSessionId sessionId = CallSessionId.generate();
    private final Instant timestamp = Instant.now();
    private final AudioFormat format = AudioFormat.PCM_16KHZ_MONO;
    private final byte[] sampleData = new byte[640]; // 20ms of 16 kHz mono PCM

    @Test
    @DisplayName("valid AudioChunk stores attributes and ensures defensive copying")
    void validAudioChunkCreationAndImmutability() {
        byte[] original = new byte[] {0x01, 0x02, 0x03, 0x04};
        AudioChunk chunk = new AudioChunk(sessionId, 42L, timestamp, original, format);

        assertThat(chunk.sessionId()).isEqualTo(sessionId);
        assertThat(chunk.sequenceNumber()).isEqualTo(42L);
        assertThat(chunk.timestamp()).isEqualTo(timestamp);
        assertThat(chunk.format()).isEqualTo(format);
        assertThat(chunk.pcmData())
                .containsExactly((byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04);

        // Mutating original array should NOT affect chunk
        original[0] = (byte) 0xFF;
        assertThat(chunk.pcmData()[0]).isEqualTo((byte) 0x01);

        // Mutating returned array from getter should NOT affect chunk
        byte[] fetched = chunk.pcmData();
        fetched[0] = (byte) 0xEE;
        assertThat(chunk.pcmData()[0]).isEqualTo((byte) 0x01);
    }

    @Test
    @DisplayName("rejects null fields")
    void nullFieldsThrow() {
        assertThatThrownBy(() -> new AudioChunk(null, 0L, timestamp, sampleData, format))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId cannot be null");

        assertThatThrownBy(() -> new AudioChunk(sessionId, 0L, null, sampleData, format))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp cannot be null");

        assertThatThrownBy(() -> new AudioChunk(sessionId, 0L, timestamp, null, format))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pcmData cannot be null");

        assertThatThrownBy(() -> new AudioChunk(sessionId, 0L, timestamp, sampleData, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("format cannot be null");
    }

    @Test
    @DisplayName("rejects negative sequence numbers")
    void negativeSequenceNumberThrows() {
        assertThatThrownBy(() -> new AudioChunk(sessionId, -1L, timestamp, sampleData, format))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNumber cannot be negative");
    }

    @Test
    @DisplayName("rejects empty payload")
    void emptyPayloadThrows() {
        assertThatThrownBy(() -> new AudioChunk(sessionId, 0L, timestamp, new byte[0], format))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pcmData cannot be empty");
    }

    @Test
    @DisplayName("rejects payloads exceeding 64 KB DoS ceiling")
    void oversizedPayloadThrows() {
        byte[] oversized = new byte[65538]; // 64 KB + 2 bytes
        assertThatThrownBy(() -> new AudioChunk(sessionId, 0L, timestamp, oversized, format))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pcmData exceeds 64 KB DoS limit");
    }

    @Test
    @DisplayName("rejects payload that is not sample-aligned (odd number of bytes for 16-bit PCM)")
    void misalignedPayloadThrows() {
        byte[] oddData = new byte[3]; // odd length
        assertThatThrownBy(() -> new AudioChunk(sessionId, 0L, timestamp, oddData, format))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pcmData is not sample-aligned");
    }

    @Test
    @DisplayName("equals and hashCode consider array content and other attributes")
    void equalsAndHashCodeContract() {
        byte[] pcm1 = new byte[] {0x01, 0x02};
        byte[] pcm2 = new byte[] {0x01, 0x02};
        byte[] pcmDifferent = new byte[] {0x01, 0x03};

        AudioChunk chunk1 = new AudioChunk(sessionId, 1L, timestamp, pcm1, format);
        AudioChunk chunk2 = new AudioChunk(sessionId, 1L, timestamp, pcm2, format);
        AudioChunk chunk3 = new AudioChunk(sessionId, 1L, timestamp, pcmDifferent, format);
        AudioChunk chunk4 = new AudioChunk(sessionId, 2L, timestamp, pcm1, format);

        assertThat(chunk1)
                .isEqualTo(chunk2)
                .hasSameHashCodeAs(chunk2)
                .isNotEqualTo(chunk3)
                .isNotEqualTo(chunk4)
                .isNotEqualTo(null)
                .isNotEqualTo("otherType");

        assertThat(chunk1.toString())
                .contains(sessionId.toString())
                .contains("sequenceNumber=1")
                .contains("pcmDataBytes=2");
    }
}
