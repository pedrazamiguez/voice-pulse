package com.voicepulse.conversation.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.shared.model.CallId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SyntheticAudioStreamGeneratorTest {

    @Test
    @DisplayName("Generated payload has expected 640-byte length and little-endian header")
    void generatePayload_hasExpectedLengthAndHeader() {
        CallId callId = CallId.generate();
        long seq = 42L;

        byte[] payload = SyntheticAudioStreamGenerator.generatePayload(callId, seq);

        assertThat(payload).hasSize(640);

        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long msb = buffer.getLong();
        long lsb = buffer.getLong();
        long recordedSeq = buffer.getLong();

        assertThat(msb).isEqualTo(callId.value().getMostSignificantBits());
        assertThat(lsb).isEqualTo(callId.value().getLeastSignificantBits());
        assertThat(recordedSeq).isEqualTo(seq);
    }

    @Test
    @DisplayName("Matches expected returns true for identical payload and false otherwise")
    void matchesExpected_validatesIntegrity() {
        CallId callId = CallId.generate();
        byte[] payload = SyntheticAudioStreamGenerator.generatePayload(callId, 10L);

        assertThat(SyntheticAudioStreamGenerator.matchesExpected(callId, 10L, payload)).isTrue();
        assertThat(SyntheticAudioStreamGenerator.matchesExpected(callId, 11L, payload)).isFalse();
        assertThat(SyntheticAudioStreamGenerator.matchesExpected(CallId.generate(), 10L, payload))
                .isFalse();
        assertThat(SyntheticAudioStreamGenerator.matchesExpected(callId, 10L, new byte[640]))
                .isFalse();
        assertThat(SyntheticAudioStreamGenerator.matchesExpected(callId, 10L, null)).isFalse();
    }

    @Test
    @DisplayName("Generate stream produces requested count of continuous frames")
    void generateStream_producesRequestedCount() {
        CallId callId = CallId.generate();
        List<byte[]> stream = SyntheticAudioStreamGenerator.generateStream(callId, 50);

        assertThat(stream).hasSize(50);
        for (int i = 0; i < stream.size(); i++) {
            assertThat(SyntheticAudioStreamGenerator.matchesExpected(callId, i, stream.get(i)))
                    .isTrue();
        }
    }

    @Test
    @DisplayName(
            "Invalid arguments are rejected with IllegalArgumentException or NullPointerException")
    void invalidArguments_throwExceptions() {
        CallId callId = CallId.generate();

        assertThatThrownBy(() -> SyntheticAudioStreamGenerator.generatePayload(null, 0L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SyntheticAudioStreamGenerator.generatePayload(callId, -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SyntheticAudioStreamGenerator.generateStream(null, 10))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SyntheticAudioStreamGenerator.generateStream(callId, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SyntheticAudioStreamGenerator.generateStream(callId, -5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
