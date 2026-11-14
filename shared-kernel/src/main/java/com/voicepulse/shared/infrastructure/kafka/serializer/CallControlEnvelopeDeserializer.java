package com.voicepulse.shared.infrastructure.kafka.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.shared.event.BackpressureSignal;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.event.CallStarted;
import com.voicepulse.shared.event.CallTerminated;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import com.voicepulse.shared.model.AudioEncoding;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * Polymorphic JSON Kafka {@link Deserializer} for {@link CallControlEnvelope} signaling events.
 *
 * <p>Reconstructs concrete domain records from JSON objects using the {@code type} discriminator,
 * ensuring domain models remain completely decoupled from Jackson annotations. Supports constructor
 * injection of a Spring-managed {@link ObjectMapper} bean while providing a default no-arg
 * constructor for Kafka's class-based deserializer SPI.
 */
public class CallControlEnvelopeDeserializer implements Deserializer<CallControlEnvelope> {

    private final ObjectMapper objectMapper;

    /**
     * Constructs a deserializer using the shared default Kafka {@link ObjectMapper} (required by
     * Kafka's class-based {@link Deserializer} SPI).
     */
    public CallControlEnvelopeDeserializer() {
        this(KafkaJsonCodecProvider.objectMapper());
    }

    /**
     * Constructs a deserializer with an explicitly injected {@link ObjectMapper} (e.g. from
     * Spring's application context).
     *
     * @param objectMapper pre-configured Jackson {@link ObjectMapper}
     */
    public CallControlEnvelopeDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

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
            JsonNode root = objectMapper.readTree(data);
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
                case "OPERATOR_REROUTE_SIGNAL" -> {
                    if (!root.hasNonNull("operatorId")) {
                        throw new SerializationException(
                                "Missing 'operatorId' field in OPERATOR_REROUTE_SIGNAL JSON");
                    }
                    if (!root.hasNonNull("bridgeEndpoint")) {
                        throw new SerializationException(
                                "Missing 'bridgeEndpoint' field in OPERATOR_REROUTE_SIGNAL JSON");
                    }
                    String operatorId = root.get("operatorId").asText();
                    String bridgeEndpoint = root.get("bridgeEndpoint").asText();
                    yield new OperatorRerouteSignal(
                            callId, sequenceNumber, timestamp, operatorId, bridgeEndpoint);
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
