package com.voicepulse.gateway.domain.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.gateway.domain.exception.InvalidAudioFrameException;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AudioFrameValidatorTest {

    private final CallSessionId sessionId = CallSessionId.generate();
    private final Instant now = Instant.now();

    @Test
    @DisplayName("valid 20ms 16 kHz mono chunk passes validation")
    void validStandardChunkPasses() {
        byte[] pcm = new byte[640]; // 20ms at 16 kHz Mono
        AudioChunk chunk = new AudioChunk(sessionId, 0L, now, pcm, AudioFormat.PCM_16KHZ_MONO);

        assertThatCode(() -> AudioFrameValidator.validate(chunk)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null chunk throws NullPointerException")
    void nullChunkThrows() {
        assertThatThrownBy(() -> AudioFrameValidator.validate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("chunk cannot be null");
    }

    @Test
    @DisplayName("validateRawPayload rejects null and empty byte arrays")
    void validateRawPayloadNullOrEmptyThrows() {
        assertThatThrownBy(() -> AudioFrameValidator.validateRawPayload(null))
                .isInstanceOf(InvalidAudioFrameException.class)
                .hasMessageContaining("pcmData cannot be null");

        assertThatThrownBy(() -> AudioFrameValidator.validateRawPayload(new byte[0]))
                .isInstanceOf(InvalidAudioFrameException.class)
                .hasMessageContaining("pcmData cannot be empty");
    }

    @Test
    @DisplayName("validateRawPayload rejects payloads exceeding 64 KB")
    void validateRawPayloadOversizedThrows() {
        byte[] oversized = new byte[65538];
        assertThatThrownBy(() -> AudioFrameValidator.validateRawPayload(oversized))
                .isInstanceOf(InvalidAudioFrameException.class)
                .hasMessageContaining("exceeds 64 KB DoS limit");
    }

    @Test
    @DisplayName("validateRawPayload rejects misaligned byte lengths for 16-bit PCM")
    void validateRawPayloadMisalignedThrows() {
        byte[] misaligned = new byte[5]; // Odd number
        assertThatThrownBy(() -> AudioFrameValidator.validateRawPayload(misaligned))
                .isInstanceOf(InvalidAudioFrameException.class)
                .hasMessageContaining("not sample-aligned");
    }
}
