package com.voicepulse.gateway.domain.model;

import com.voicepulse.gateway.domain.exception.InvalidAudioFrameException;
import com.voicepulse.shared.model.AudioChunk;
import java.util.Objects;

/** Domain validator enforcing telephony invariants, DoS byte limits, and PCM sample alignment. */
public final class AudioFrameValidator {

    /** Maximum permissible raw audio payload size in bytes (64 KB DoS ceiling). */
    public static final int MAX_FRAME_SIZE_BYTES = 65536;

    private AudioFrameValidator() {
        // Utility class
    }

    /** Validates an entire immutable {@link AudioChunk}. */
    public static void validate(AudioChunk chunk) {
        Objects.requireNonNull(chunk, "chunk cannot be null");
        validateRawPayload(chunk.pcmData());
        if (chunk.sequenceNumber() < 0) {
            throw new InvalidAudioFrameException(
                    "sequenceNumber cannot be negative: " + chunk.sequenceNumber());
        }
    }

    /** Validates raw binary PCM byte array before chunk construction or Netty forwarding. */
    public static void validateRawPayload(byte[] pcmData) {
        if (pcmData == null) {
            throw new InvalidAudioFrameException("pcmData cannot be null");
        }
        if (pcmData.length == 0) {
            throw new InvalidAudioFrameException("pcmData cannot be empty");
        }
        if (pcmData.length > MAX_FRAME_SIZE_BYTES) {
            throw new InvalidAudioFrameException(
                    "pcmData exceeds 64 KB DoS limit: " + pcmData.length + " bytes");
        }
        if (pcmData.length % 2 != 0) {
            throw new InvalidAudioFrameException(
                    "pcmData is not sample-aligned: length "
                            + pcmData.length
                            + " is not divisible by 2 (16-bit PCM)");
        }
    }
}
