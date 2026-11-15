package com.voicepulse.shared.infrastructure.kafka.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voicepulse.shared.event.BackpressureSignal;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.event.CallStarted;
import com.voicepulse.shared.event.CallTerminated;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Polymorphic JSON Kafka {@link Serializer} for {@link CallControlEnvelope} signaling events.
 *
 * <p>Translates domain sealed interface records into JSON objects with an explicit {@code type}
 * discriminator, keeping domain classes pure and free of framework serialization annotations.
 * Supports constructor injection of a Spring-managed {@link ObjectMapper} bean while providing a
 * default no-arg constructor for Kafka's class-based serializer SPI.
 */
public class CallControlEnvelopeSerializer implements Serializer<CallControlEnvelope> {

    private final ObjectMapper objectMapper;

    /**
     * Constructs a serializer using the shared default Kafka {@link ObjectMapper} (required by
     * Kafka's class-based {@link Serializer} SPI).
     */
    public CallControlEnvelopeSerializer() {
        this(KafkaJsonCodecProvider.objectMapper());
    }

    /**
     * Constructs a serializer with an explicitly injected {@link ObjectMapper} (e.g. from Spring's
     * application context).
     *
     * @param objectMapper pre-configured Jackson {@link ObjectMapper}
     */
    public CallControlEnvelopeSerializer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No custom configuration required
    }

    @Override
    public byte[] serialize(String topic, CallControlEnvelope data) {
        if (data == null) {
            return null;
        }

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("callId", data.callId().toString());
            root.put("sequenceNumber", data.sequenceNumber());
            root.put("timestamp", data.timestamp().toString());

            switch (data) {
                case CallStarted started -> {
                    root.put("type", "CALL_STARTED");
                    ObjectNode formatNode = root.putObject("format");
                    formatNode.put("sampleRateHz", started.format().sampleRateHz());
                    formatNode.put("bitDepth", started.format().bitDepth());
                    formatNode.put("channels", started.format().channels());
                    formatNode.put("encoding", started.format().encoding().name());
                }
                case CallTerminated terminated -> {
                    root.put("type", "CALL_TERMINATED");
                    root.put("reason", terminated.reason());
                }
                case BargeInInterrupt bargeIn -> {
                    root.put("type", "BARGE_IN_INTERRUPT");
                    root.put("playbackOffsetMs", bargeIn.playbackOffsetMs());
                }
                case BackpressureSignal backpressure -> {
                    root.put("type", "BACKPRESSURE_SIGNAL");
                    root.put("pauseRequested", backpressure.pauseRequested());
                    root.put("bufferFillRatioPercent", backpressure.bufferFillRatioPercent());
                }
                case OperatorRerouteSignal reroute -> {
                    root.put("type", "OPERATOR_REROUTE_SIGNAL");
                    root.put("operatorId", reroute.operatorId());
                    root.put("bridgeEndpoint", reroute.bridgeEndpoint());
                }
            }

            return objectMapper.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Failed to serialize CallControlEnvelope to JSON", e);
        }
    }

    @Override
    public void close() {
        // Stateless, no resources to release
    }
}
