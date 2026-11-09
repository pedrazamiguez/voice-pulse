package com.voicepulse.shared.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AudioChunkEnvelope Domain Model Tests")
class AudioChunkEnvelopeTest {

    private static final CallId CALL_ID = CallId.generate();
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final AudioFormat FORMAT = AudioFormat.PCM_16KHZ_MONO;
    private static final byte[] VALID_PAYLOAD = new byte[640]; // 20ms at 16kHz 16-bit mono

    @Test
    @DisplayName("Should successfully construct an AudioChunkEnvelope with valid attributes")
    void shouldConstructValidEnvelope() {
        AudioChunkEnvelope envelope =
                new AudioChunkEnvelope(CALL_ID, 0L, NOW, FORMAT, VALID_PAYLOAD);

        assertThat(envelope.callId()).isEqualTo(CALL_ID);
        assertThat(envelope.sequenceNumber()).isZero();
        assertThat(envelope.timestamp()).isEqualTo(NOW);
        assertThat(envelope.format()).isEqualTo(FORMAT);
        assertThat(envelope.payload()).isEqualTo(VALID_PAYLOAD);
    }

    @Test
    @DisplayName("Should enforce null checks on mandatory components")
    void shouldRejectNullComponents() {
        assertThatThrownBy(() -> new AudioChunkEnvelope(null, 0L, NOW, FORMAT, VALID_PAYLOAD))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");

        assertThatThrownBy(() -> new AudioChunkEnvelope(CALL_ID, 0L, null, FORMAT, VALID_PAYLOAD))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");

        assertThatThrownBy(() -> new AudioChunkEnvelope(CALL_ID, 0L, NOW, null, VALID_PAYLOAD))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("format");

        assertThatThrownBy(() -> new AudioChunkEnvelope(CALL_ID, 0L, NOW, FORMAT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }

    @Test
    @DisplayName("Should reject negative sequence numbers")
    void shouldRejectNegativeSequenceNumber() {
        assertThatThrownBy(() -> new AudioChunkEnvelope(CALL_ID, -1L, NOW, FORMAT, VALID_PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNumber cannot be negative");
    }

    @Test
    @DisplayName("Should reject empty payload")
    void shouldRejectEmptyPayload() {
        assertThatThrownBy(() -> new AudioChunkEnvelope(CALL_ID, 0L, NOW, FORMAT, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload cannot be empty");
    }

    @Test
    @DisplayName("Should reject payload exceeding 64 KB DoS limit")
    void shouldRejectPayloadExceedingLimit() {
        byte[] oversized = new byte[AudioChunkEnvelope.MAX_PAYLOAD_SIZE_BYTES + 2];
        assertThatThrownBy(() -> new AudioChunkEnvelope(CALL_ID, 0L, NOW, FORMAT, oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 64 KB DoS limit");
    }

    @Test
    @DisplayName("Should reject non-sample-aligned payload")
    void shouldRejectNonSampleAlignedPayload() {
        byte[] unaligned = new byte[3]; // Not divisible by 2 bytesPerSample
        assertThatThrownBy(() -> new AudioChunkEnvelope(CALL_ID, 0L, NOW, FORMAT, unaligned))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not sample-aligned");
    }

    @Test
    @DisplayName("Should enforce defensive copying on input and output arrays")
    void shouldDefensivelyCopyPayload() {
        byte[] original = new byte[] {0x10, 0x20};
        AudioChunkEnvelope envelope = new AudioChunkEnvelope(CALL_ID, 1L, NOW, FORMAT, original);

        // Mutating input array after construction
        original[0] = (byte) 0xFF;
        assertThat(envelope.payload()[0]).isEqualTo((byte) 0x10);

        // Mutating getter return value
        byte[] fromGetter = envelope.payload();
        fromGetter[0] = (byte) 0xEE;
        assertThat(envelope.payload()[0]).isEqualTo((byte) 0x10);
    }

    @Test
    @DisplayName("Should convert bidirectionally between AudioChunk and AudioChunkEnvelope")
    void shouldConvertBidirectionallyWithAudioChunk() {
        CallSessionId sessionId = CALL_ID.toSessionId();
        AudioChunk chunk = new AudioChunk(sessionId, 42L, NOW, VALID_PAYLOAD, FORMAT);

        AudioChunkEnvelope envelope = AudioChunkEnvelope.from(chunk);
        assertThat(envelope.callId()).isEqualTo(CALL_ID);
        assertThat(envelope.sequenceNumber()).isEqualTo(42L);
        assertThat(envelope.timestamp()).isEqualTo(NOW);
        assertThat(envelope.format()).isEqualTo(FORMAT);
        assertThat(envelope.payload()).isEqualTo(VALID_PAYLOAD);

        AudioChunk reconstructed = envelope.toAudioChunk();
        assertThat(reconstructed).isEqualTo(chunk);
    }

    @Test
    @DisplayName("Should implement equals and hashCode based on value and payload content")
    void shouldImplementEqualsAndHashCode() {
        byte[] payload1 = new byte[] {0x01, 0x02};
        byte[] payload2 = new byte[] {0x01, 0x02};
        byte[] payload3 = new byte[] {0x03, 0x04};

        AudioChunkEnvelope env1 = new AudioChunkEnvelope(CALL_ID, 5L, NOW, FORMAT, payload1);
        AudioChunkEnvelope env2 = new AudioChunkEnvelope(CALL_ID, 5L, NOW, FORMAT, payload2);
        AudioChunkEnvelope env3 = new AudioChunkEnvelope(CALL_ID, 5L, NOW, FORMAT, payload3);

        assertThat(env1).isEqualTo(env2);
        assertThat(env1.hashCode()).isEqualTo(env2.hashCode());
        assertThat(env1).isNotEqualTo(env3);
        assertThat(env1).isNotEqualTo(null);
    }

    @Test
    @DisplayName("ToString should describe metadata without dumping raw bytes")
    void toStringShouldDescribeMetadata() {
        AudioChunkEnvelope envelope =
                new AudioChunkEnvelope(CALL_ID, 0L, NOW, FORMAT, VALID_PAYLOAD);
        String str = envelope.toString();

        assertThat(str)
                .contains("AudioChunkEnvelope")
                .contains(CALL_ID.toString())
                .contains("payloadBytes=640");
    }
}
