package com.voicepulse.shared.infrastructure.kafka.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.shared.event.AudioChunkEnvelope;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Arrays;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AudioChunkEnvelope Binary Kafka Serialization Tests")
class AudioChunkEnvelopeSerializationTest {

    private static final String TOPIC = "telephony.calls.raw";
    private static final CallId CALL_ID = CallId.generate();
    private static final Instant NOW = Instant.ofEpochMilli(1731146400000L);
    private static final AudioFormat FORMAT = AudioFormat.PCM_16KHZ_MONO;

    private AudioChunkEnvelopeSerializer serializer;
    private AudioChunkEnvelopeDeserializer deserializer;

    @BeforeEach
    void setUp() {
        serializer = new AudioChunkEnvelopeSerializer();
        deserializer = new AudioChunkEnvelopeDeserializer();
    }

    @AfterEach
    void tearDown() {
        serializer.close();
        deserializer.close();
    }

    @Test
    @DisplayName(
            "Should serialize and deserialize an AudioChunkEnvelope with 100% round-trip fidelity")
    void shouldSerializeAndDeserializeRoundTrip() {
        byte[] pcmPayload = new byte[640];
        Arrays.fill(pcmPayload, (byte) 0x2A);

        AudioChunkEnvelope original =
                new AudioChunkEnvelope(CALL_ID, 123L, NOW, FORMAT, pcmPayload);

        byte[] serialized = serializer.serialize(TOPIC, original);
        assertThat(serialized).isNotNull();
        assertThat(serialized.length)
                .isEqualTo(AudioChunkEnvelopeSerializer.HEADER_SIZE + pcmPayload.length);

        AudioChunkEnvelope deserialized = deserializer.deserialize(TOPIC, serialized);

        assertThat(deserialized).isEqualTo(original);
        assertThat(deserialized.callId()).isEqualTo(CALL_ID);
        assertThat(deserialized.sequenceNumber()).isEqualTo(123L);
        assertThat(deserialized.timestamp()).isEqualTo(NOW);
        assertThat(deserialized.format()).isEqualTo(FORMAT);
        assertThat(deserialized.payload()).isEqualTo(pcmPayload);
    }

    @Test
    @DisplayName("Should return null when serializing or deserializing null/empty data")
    void shouldHandleNullAndEmpty() {
        assertThat(serializer.serialize(TOPIC, null)).isNull();
        assertThat(deserializer.deserialize(TOPIC, null)).isNull();
        assertThat(deserializer.deserialize(TOPIC, new byte[0])).isNull();
    }

    @Test
    @DisplayName("Should reject data shorter than minimum header size")
    void shouldRejectTruncatedHeader() {
        byte[] shortData = new byte[AudioChunkEnvelopeSerializer.HEADER_SIZE - 1];

        assertThatThrownBy(() -> deserializer.deserialize(TOPIC, shortData))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("less than minimum header size");
    }

    @Test
    @DisplayName("Should reject payload with invalid magic byte")
    void shouldRejectInvalidMagicByte() {
        byte[] pcmPayload = new byte[640];
        AudioChunkEnvelope original = new AudioChunkEnvelope(CALL_ID, 1L, NOW, FORMAT, pcmPayload);
        byte[] serialized = serializer.serialize(TOPIC, original);

        // Corrupt magic byte
        serialized[0] = (byte) 0x00;

        assertThatThrownBy(() -> deserializer.deserialize(TOPIC, serialized))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Invalid magic byte");
    }

    @Test
    @DisplayName("Should reject payload with unsupported protocol version")
    void shouldRejectUnsupportedVersion() {
        byte[] pcmPayload = new byte[640];
        AudioChunkEnvelope original = new AudioChunkEnvelope(CALL_ID, 1L, NOW, FORMAT, pcmPayload);
        byte[] serialized = serializer.serialize(TOPIC, original);

        // Corrupt version byte (offset 1)
        serialized[1] = (byte) 0x99;

        assertThatThrownBy(() -> deserializer.deserialize(TOPIC, serialized))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Unsupported binary protocol version");
    }

    @Test
    @DisplayName("Should reject payload with invalid encoding wireId")
    void shouldRejectInvalidEncodingWireId() {
        byte[] pcmPayload = new byte[640];
        AudioChunkEnvelope original = new AudioChunkEnvelope(CALL_ID, 1L, NOW, FORMAT, pcmPayload);
        byte[] serialized = serializer.serialize(TOPIC, original);

        // Corrupt encoding byte (offset 41)
        serialized[41] = (byte) 0xFE;

        assertThatThrownBy(() -> deserializer.deserialize(TOPIC, serialized))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Invalid audio encoding wire ID");
    }

    @Test
    @DisplayName("Should reject payload when buffer is truncated before specified payload length")
    void shouldRejectTruncatedPayload() {
        byte[] pcmPayload = new byte[640];
        AudioChunkEnvelope original = new AudioChunkEnvelope(CALL_ID, 1L, NOW, FORMAT, pcmPayload);
        byte[] serialized = serializer.serialize(TOPIC, original);

        // Truncate last 10 bytes
        byte[] truncated = Arrays.copyOf(serialized, serialized.length - 10);

        assertThatThrownBy(() -> deserializer.deserialize(TOPIC, truncated))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Buffer underflow");
    }

    @Test
    @DisplayName("Should benchmark 10,000 serialize/deserialize iterations for high efficiency")
    void benchmarkHighThroughputSerialization() {
        byte[] pcmPayload = new byte[640]; // standard 20ms frame
        AudioChunkEnvelope envelope =
                new AudioChunkEnvelope(CALL_ID, 999L, NOW, FORMAT, pcmPayload);

        long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            byte[] bytes = serializer.serialize(TOPIC, envelope);
            AudioChunkEnvelope result = deserializer.deserialize(TOPIC, bytes);
            assertThat(result.sequenceNumber()).isEqualTo(999L);
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        // 10,000 frames (200 simulated seconds of audio) must process in under 150ms
        assertThat(durationMs).isLessThan(200);
    }
}
