package com.voicepulse.shared.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import java.time.Instant;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AudioChunk Domain Model Tests")
class AudioChunkTest {

    private final CallSessionId sessionId = CallSessionId.generate();
    private final Instant timestamp = Instant.now();
    private final AudioFormat format = AudioFormat.PCM_16KHZ_MONO;
    private final byte[] sampleData = new byte[640]; // 20ms of 16 kHz mono PCM

    private AudioChunk createValidChunk() {
        return Instancio.of(AudioChunk.class)
                .set(field(AudioChunk::sessionId), sessionId)
                .set(field(AudioChunk::sequenceNumber), 0L)
                .set(field(AudioChunk::timestamp), timestamp)
                .set(field(AudioChunk::pcmData), sampleData)
                .set(field(AudioChunk::format), format)
                .create();
    }

    @Test
    @DisplayName("valid AudioChunk stores attributes and ensures defensive copying")
    void validAudioChunkCreationAndImmutability() {
        byte[] original = new byte[] {0x01, 0x02, 0x03, 0x04};
        AudioChunk chunk =
                Instancio.of(AudioChunk.class)
                        .set(field(AudioChunk::sessionId), sessionId)
                        .set(field(AudioChunk::sequenceNumber), 42L)
                        .set(field(AudioChunk::timestamp), timestamp)
                        .set(field(AudioChunk::pcmData), original)
                        .set(field(AudioChunk::format), format)
                        .create();

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
        AudioChunk base = createValidChunk();
        assertThatThrownBy(
                        () ->
                                new AudioChunk(
                                        null,
                                        base.sequenceNumber(),
                                        base.timestamp(),
                                        base.pcmData(),
                                        base.format()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId cannot be null");

        assertThatThrownBy(
                        () ->
                                new AudioChunk(
                                        base.sessionId(),
                                        base.sequenceNumber(),
                                        null,
                                        base.pcmData(),
                                        base.format()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp cannot be null");

        assertThatThrownBy(
                        () ->
                                new AudioChunk(
                                        base.sessionId(),
                                        base.sequenceNumber(),
                                        base.timestamp(),
                                        null,
                                        base.format()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pcmData cannot be null");

        assertThatThrownBy(
                        () ->
                                new AudioChunk(
                                        base.sessionId(),
                                        base.sequenceNumber(),
                                        base.timestamp(),
                                        base.pcmData(),
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("format cannot be null");
    }

    @Test
    @DisplayName("rejects negative sequence numbers")
    void negativeSequenceNumberThrows() {
        AudioChunk base = createValidChunk();
        assertThatThrownBy(
                        () ->
                                new AudioChunk(
                                        base.sessionId(),
                                        -1L,
                                        base.timestamp(),
                                        base.pcmData(),
                                        base.format()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNumber cannot be negative");
    }

    @Test
    @DisplayName("rejects empty payload")
    void emptyPayloadThrows() {
        AudioChunk base = createValidChunk();
        assertThatThrownBy(
                        () ->
                                new AudioChunk(
                                        base.sessionId(),
                                        base.sequenceNumber(),
                                        base.timestamp(),
                                        new byte[0],
                                        base.format()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pcmData cannot be empty");
    }

    @Test
    @DisplayName("rejects payloads exceeding 64 KB DoS ceiling")
    void oversizedPayloadThrows() {
        AudioChunk base = createValidChunk();
        byte[] oversized = new byte[65538]; // 64 KB + 2 bytes
        assertThatThrownBy(
                        () ->
                                new AudioChunk(
                                        base.sessionId(),
                                        base.sequenceNumber(),
                                        base.timestamp(),
                                        oversized,
                                        base.format()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pcmData exceeds 64 KB DoS limit");
    }

    @Test
    @DisplayName("rejects payload that is not sample-aligned (odd number of bytes for 16-bit PCM)")
    void misalignedPayloadThrows() {
        AudioChunk base = createValidChunk();
        byte[] oddData = new byte[3]; // odd length
        assertThatThrownBy(
                        () ->
                                new AudioChunk(
                                        base.sessionId(),
                                        base.sequenceNumber(),
                                        base.timestamp(),
                                        oddData,
                                        base.format()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pcmData is not sample-aligned");
    }

    @Test
    @DisplayName("equals and hashCode consider array content and other attributes")
    void equalsAndHashCodeContract() {
        byte[] pcm1 = new byte[] {0x01, 0x02};
        byte[] pcm2 = new byte[] {0x01, 0x02};
        byte[] pcmDifferent = new byte[] {0x01, 0x03};

        AudioChunk chunk1 =
                Instancio.of(AudioChunk.class)
                        .set(field(AudioChunk::sessionId), sessionId)
                        .set(field(AudioChunk::sequenceNumber), 1L)
                        .set(field(AudioChunk::timestamp), timestamp)
                        .set(field(AudioChunk::pcmData), pcm1)
                        .set(field(AudioChunk::format), format)
                        .create();

        AudioChunk chunk2 =
                Instancio.of(AudioChunk.class)
                        .set(field(AudioChunk::sessionId), sessionId)
                        .set(field(AudioChunk::sequenceNumber), 1L)
                        .set(field(AudioChunk::timestamp), timestamp)
                        .set(field(AudioChunk::pcmData), pcm2)
                        .set(field(AudioChunk::format), format)
                        .create();

        AudioChunk chunkDiff =
                Instancio.of(AudioChunk.class)
                        .set(field(AudioChunk::sessionId), sessionId)
                        .set(field(AudioChunk::sequenceNumber), 1L)
                        .set(field(AudioChunk::timestamp), timestamp)
                        .set(field(AudioChunk::pcmData), pcmDifferent)
                        .set(field(AudioChunk::format), format)
                        .create();

        assertThat(chunk1).isEqualTo(chunk2);
        assertThat(chunk1.hashCode()).isEqualTo(chunk2.hashCode());
        assertThat(chunk1).isNotEqualTo(chunkDiff);
        assertThat(chunk1).isNotEqualTo(null);
        assertThat(chunk1).isNotEqualTo("string");
    }
}
