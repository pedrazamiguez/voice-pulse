package com.voicepulse.shared.infrastructure.kafka.serializer;

import static com.voicepulse.shared.infrastructure.kafka.serializer.AudioChunkEnvelopeSerializer.HEADER_SIZE;
import static com.voicepulse.shared.infrastructure.kafka.serializer.AudioChunkEnvelopeSerializer.MAGIC_BYTE;
import static com.voicepulse.shared.infrastructure.kafka.serializer.AudioChunkEnvelopeSerializer.VERSION_1;

import com.voicepulse.shared.event.AudioChunkEnvelope;
import com.voicepulse.shared.model.AudioEncoding;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * High-performance, binary Kafka {@link Deserializer} for {@link AudioChunkEnvelope}.
 *
 * <p>Parses incoming byte payloads according to the VoicePulse 46-byte framing protocol with
 * defensive boundary checks against malformed or truncated audio streams.
 */
public class AudioChunkEnvelopeDeserializer implements Deserializer<AudioChunkEnvelope> {

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No custom configuration required
    }

    @Override
    public AudioChunkEnvelope deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        if (data.length < HEADER_SIZE) {
            throw new SerializationException(
                    "Payload length "
                            + data.length
                            + " is less than minimum header size "
                            + HEADER_SIZE);
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);

        byte magic = buffer.get();
        if (magic != MAGIC_BYTE) {
            throw new SerializationException(
                    String.format(
                            "Invalid magic byte: 0x%02X, expected 0x%02X", magic, MAGIC_BYTE));
        }

        byte version = buffer.get();
        if (version != VERSION_1) {
            throw new SerializationException("Unsupported binary protocol version: " + version);
        }

        long msb = buffer.getLong();
        long lsb = buffer.getLong();
        CallId callId = new CallId(new UUID(msb, lsb));

        long sequenceNumber = buffer.getLong();
        long epochMs = buffer.getLong();
        Instant timestamp = Instant.ofEpochMilli(epochMs);

        int sampleRateHz = buffer.getInt();
        int bitDepth = buffer.getShort() & 0xFFFF;
        int channels = buffer.get() & 0xFF;
        byte encodingWireId = buffer.get();

        AudioEncoding encoding;
        try {
            encoding = AudioEncoding.fromWireId(encodingWireId);
        } catch (IllegalArgumentException e) {
            throw new SerializationException(
                    "Invalid audio encoding wire ID: " + encodingWireId, e);
        }
        AudioFormat format = new AudioFormat(sampleRateHz, bitDepth, channels, encoding);

        int payloadLength = buffer.getInt();
        if (payloadLength < 0 || payloadLength > AudioChunkEnvelope.MAX_PAYLOAD_SIZE_BYTES) {
            throw new SerializationException("Invalid payload length: " + payloadLength);
        }

        if (buffer.remaining() < payloadLength) {
            throw new SerializationException(
                    "Buffer underflow: remaining "
                            + buffer.remaining()
                            + " < expected "
                            + payloadLength);
        }

        byte[] payload = new byte[payloadLength];
        buffer.get(payload);

        return new AudioChunkEnvelope(callId, sequenceNumber, timestamp, format, payload);
    }

    @Override
    public void close() {
        // Stateless, no resources to release
    }
}
