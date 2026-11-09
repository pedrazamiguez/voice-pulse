package com.voicepulse.shared.infrastructure.kafka.serializer;

import com.voicepulse.shared.event.AudioChunkEnvelope;
import java.nio.ByteBuffer;
import java.util.Map;
import org.apache.kafka.common.serialization.Serializer;

/**
 * High-performance, zero-allocation binary Kafka {@link Serializer} for {@link AudioChunkEnvelope}.
 *
 * <p>Encodes audio chunks into a compact 46-byte binary header followed by raw PCM sample bytes,
 * avoiding intermediate streams and unnecessary memory allocations.
 */
public class AudioChunkEnvelopeSerializer implements Serializer<AudioChunkEnvelope> {

    /** Magic identifier byte ('V' in ASCII). */
    public static final byte MAGIC_BYTE = 0x56;

    /** Binary protocol version 1. */
    public static final byte VERSION_1 = 0x01;

    /** Fixed binary framing header size in bytes. */
    public static final int HEADER_SIZE = 46;

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No custom configuration required
    }

    @Override
    public byte[] serialize(String topic, AudioChunkEnvelope data) {
        if (data == null) {
            return null;
        }

        byte[] payload = data.payload();
        int totalSize = HEADER_SIZE + payload.length;
        byte[] result = new byte[totalSize];
        ByteBuffer buffer = ByteBuffer.wrap(result);

        buffer.put(MAGIC_BYTE);
        buffer.put(VERSION_1);
        buffer.putLong(data.callId().value().getMostSignificantBits());
        buffer.putLong(data.callId().value().getLeastSignificantBits());
        buffer.putLong(data.sequenceNumber());
        buffer.putLong(data.timestamp().toEpochMilli());
        buffer.putInt(data.format().sampleRateHz());
        buffer.putShort((short) data.format().bitDepth());
        buffer.put((byte) data.format().channels());
        buffer.put(data.format().encoding().wireId());
        buffer.putInt(payload.length);
        buffer.put(payload);

        return result;
    }

    @Override
    public void close() {
        // Stateless, no resources to release
    }
}
