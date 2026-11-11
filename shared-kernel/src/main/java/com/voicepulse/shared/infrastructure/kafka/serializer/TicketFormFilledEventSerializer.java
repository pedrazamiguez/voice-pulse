package com.voicepulse.shared.infrastructure.kafka.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

/**
 * JSON Kafka {@link Serializer} for {@link TicketFormFilledEvent} envelopes published to {@code
 * telephony.tickets.intent}.
 *
 * <p>Supports constructor injection of a Spring-managed {@link ObjectMapper} bean while providing a
 * default no-arg constructor for Kafka's class-based serializer SPI.
 */
public class TicketFormFilledEventSerializer implements Serializer<TicketFormFilledEvent> {

    private final ObjectMapper objectMapper;

    /**
     * Constructs a serializer using the shared default Kafka {@link ObjectMapper} (required by
     * Kafka's class-based {@link Serializer} SPI).
     */
    public TicketFormFilledEventSerializer() {
        this(KafkaJsonCodecProvider.objectMapper());
    }

    /**
     * Constructs a serializer with an explicitly injected {@link ObjectMapper} (e.g. from Spring's
     * application context).
     *
     * @param objectMapper pre-configured Jackson {@link ObjectMapper}
     */
    public TicketFormFilledEventSerializer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No custom configuration required
    }

    @Override
    public byte[] serialize(String topic, TicketFormFilledEvent data) {
        if (data == null) {
            return null;
        }

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("callId", data.callId().toString());
            root.put("ticketId", data.ticketId().toString());
            root.put("passengerName", data.passengerName());
            root.put("pnr", data.pnr().value());
            root.put("originalFlightNumber", data.originalFlightNumber().value());
            root.put("disruptionReason", data.disruptionReason().name());
            root.put("rebookingPreference", data.rebookingPreference().name());
            root.put("hotelRequired", data.hotelRequired());
            root.put("specialAssistance", data.specialAssistance());
            root.put("targetSkillGroup", data.targetSkillGroup());
            root.put("timestamp", data.timestamp().toString());

            ObjectNode payloadNode = root.putObject("payload");
            for (Map.Entry<String, Object> entry : data.payload().entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                switch (val) {
                    case null -> payloadNode.putNull(key);
                    case String s -> payloadNode.put(key, s);
                    case Integer i -> payloadNode.put(key, i);
                    case Long l -> payloadNode.put(key, l);
                    case Double d -> payloadNode.put(key, d);
                    case Float f -> payloadNode.put(key, f);
                    case Boolean b -> payloadNode.put(key, b);
                    default -> payloadNode.put(key, val.toString());
                }
            }

            return objectMapper.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new SerializationException(
                    "Failed to serialize TicketFormFilledEvent to JSON", e);
        }
    }

    @Override
    public void close() {
        // Stateless, no resources to release
    }
}
