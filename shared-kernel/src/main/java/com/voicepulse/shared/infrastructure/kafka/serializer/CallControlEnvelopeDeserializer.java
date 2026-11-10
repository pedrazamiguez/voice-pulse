package com.voicepulse.shared.infrastructure.kafka.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.voicepulse.shared.event.BackpressureSignal;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.event.CallStarted;
import com.voicepulse.shared.event.CallTerminated;
import com.voicepulse.shared.model.AudioEncoding;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * Polymorphic JSON Kafka {@link Deserializer} for {@link CallControlEnvelope} signaling events.
 *
 * <p>Reconstructs concrete domain records from JSON objects using the {@code type} discriminator,
 * ensuring domain models remain completely decoupled from Jackson annotations.
 */
public class CallControlEnvelopeDeserializer implements Deserializer<CallControlEnvelope> {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No custom configuration required
    }

    @Override
    public CallControlEnvelope deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(data);
            if (!root.hasNonNull("type")) {
                throw new SerializationException(
                        "Missing 'type' discriminator field in call control JSON");
            }

            String type = root.get("type").asText();
            CallId callId = CallId.fromString(root.path("callId").asText());
            long sequenceNumber = root.path("sequenceNumber").asLong();
            Instant timestamp = Instant.parse(root.path("timestamp").asText());

            return switch (type) {
                case "CALL_STARTED" -> {
                    JsonNode fmtNode = root.path("format");
                    int sampleRate = fmtNode.path("sampleRateHz").asInt();
                    int bitDepth = fmtNode.path("bitDepth").asInt();
                    int channels = fmtNode.path("channels").asInt();
                    String encodingStr = fmtNode.path("encoding").asText("LINEAR_PCM");
                    AudioEncoding encoding = AudioEncoding.valueOf(encodingStr);
                    AudioFormat format = new AudioFormat(sampleRate, bitDepth, channels, encoding);
                    yield new CallStarted(callId, sequenceNumber, timestamp, format);
                }
                case "CALL_TERMINATED" -> {
                    String reason = root.path("reason").asText();
                    yield new CallTerminated(callId, sequenceNumber, timestamp, reason);
                }
                case "BARGE_IN_INTERRUPT" -> {
                    long offset = root.path("playbackOffsetMs").asLong(0L);
                    yield new BargeInInterrupt(callId, sequenceNumber, timestamp, offset);
                }
                case "BACKPRESSURE_SIGNAL" -> {
                    boolean pause = root.path("pauseRequested").asBoolean();
                    int ratio = root.path("bufferFillRatioPercent").asInt();
                    yield new BackpressureSignal(callId, sequenceNumber, timestamp, pause, ratio);
                }
                default ->
                        throw new SerializationException(
                                "Unknown CallControlEnvelope type: " + type);
            };
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new SerializationException(
                    "Failed to deserialize CallControlEnvelope JSON payload", e);
        }
    }

    @Override
    public void close() {
        // Stateless, no resources to release
    }
}
