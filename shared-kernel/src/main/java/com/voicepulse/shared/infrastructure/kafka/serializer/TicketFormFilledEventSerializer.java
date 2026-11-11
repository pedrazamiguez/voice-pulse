package com.voicepulse.shared.infrastructure.kafka.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import java.util.Map;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

/**
 * JSON Kafka {@link Serializer} for {@link TicketFormFilledEvent} envelopes published to {@code
 * telephony.tickets.intent}.
 *
 * <p>Serializes pure domain records without requiring Jackson annotations on domain models.
 */
public class TicketFormFilledEventSerializer implements Serializer<TicketFormFilledEvent> {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
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

            return OBJECT_MAPPER.writeValueAsBytes(root);
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
