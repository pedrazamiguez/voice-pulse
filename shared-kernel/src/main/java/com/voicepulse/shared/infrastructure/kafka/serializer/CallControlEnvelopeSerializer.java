package com.voicepulse.shared.infrastructure.kafka.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.voicepulse.shared.event.BackpressureSignal;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.event.CallStarted;
import com.voicepulse.shared.event.CallTerminated;
import java.util.Map;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Polymorphic JSON Kafka {@link Serializer} for {@link CallControlEnvelope} signaling events.
 *
 * <p>Translates domain sealed interface records into JSON objects with an explicit {@code type}
 * discriminator, keeping domain classes pure and free of framework serialization annotations.
 */
public class CallControlEnvelopeSerializer implements Serializer<CallControlEnvelope> {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
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
            }

            return OBJECT_MAPPER.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new SerializationException("Failed to serialize CallControlEnvelope to JSON", e);
        }
    }

    @Override
    public void close() {
        // Stateless, no resources to release
    }
}
