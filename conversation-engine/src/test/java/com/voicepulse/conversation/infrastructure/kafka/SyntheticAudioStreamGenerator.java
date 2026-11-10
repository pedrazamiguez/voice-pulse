package com.voicepulse.conversation.infrastructure.kafka;

import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic synthetic 20ms Linear PCM audio frame generator for end-to-end integration testing.
 *
 * <p>Produces 640-byte sample-aligned 16 kHz 16-bit mono PCM chunks with embedded sequence and
 * session identifiers for bit-for-bit payload integrity validation.
 */
public final class SyntheticAudioStreamGenerator {

    /** Standard frame duration in milliseconds per RFC-0001 §2. */
    public static final int FRAME_DURATION_MS = 20;

    /** Expected frame size in bytes for 20ms of 16 kHz 16-bit mono PCM (640 bytes). */
    public static final int FRAME_SIZE_BYTES =
            AudioFormat.PCM_16KHZ_MONO.frameSizeBytes(FRAME_DURATION_MS);

    private SyntheticAudioStreamGenerator() {
        // Utility class
    }

    /**
     * Generates a deterministic 640-byte synthetic PCM frame payload.
     *
     * @param callId call session identifier
     * @param sequenceNumber monotonic sequence number
     * @return 640-byte binary PCM payload
     */
    public static byte[] generatePayload(CallId callId, long sequenceNumber) {
        Objects.requireNonNull(callId, "callId cannot be null");
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber cannot be negative");
        }

        byte[] payload = new byte[FRAME_SIZE_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putLong(callId.value().getMostSignificantBits());
        buffer.putLong(callId.value().getLeastSignificantBits());
        buffer.putLong(sequenceNumber);

        for (int i = 24; i < FRAME_SIZE_BYTES; i += 2) {
            short sample = (short) (((sequenceNumber * 31L) + i) % Short.MAX_VALUE);
            buffer.putShort(sample);
        }

        return payload;
    }

    /**
     * Generates a list of continuous synthetic frame payloads for the specified count.
     *
     * @param callId call session identifier
     * @param frameCount total number of frames to generate
     * @return ordered list of byte array payloads
     */
    public static List<byte[]> generateStream(CallId callId, int frameCount) {
        Objects.requireNonNull(callId, "callId cannot be null");
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be positive");
        }

        List<byte[]> frames = new ArrayList<>(frameCount);
        for (long seq = 0; seq < frameCount; seq++) {
            frames.add(generatePayload(callId, seq));
        }
        return List.copyOf(frames);
    }

    /**
     * Asserts whether the given payload matches the expected deterministic synthetic audio pattern.
     *
     * @param callId expected call session identifier
     * @param sequenceNumber expected sequence number
     * @param actualPayload ingested payload bytes
     * @return true if payloads match bit-for-bit
     */
    public static boolean matchesExpected(
            CallId callId, long sequenceNumber, byte[] actualPayload) {
        if (actualPayload == null || actualPayload.length != FRAME_SIZE_BYTES) {
            return false;
        }
        byte[] expected = generatePayload(callId, sequenceNumber);
        return Arrays.equals(expected, actualPayload);
    }
}
