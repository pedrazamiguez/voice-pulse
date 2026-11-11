package com.voicepulse.shared.infrastructure.kafka.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * JSON Kafka {@link Deserializer} for {@link TicketFormFilledEvent} envelopes consumed from {@code
 * telephony.tickets.intent}.
 *
 * <p>Supports constructor injection of a Spring-managed {@link ObjectMapper} bean while providing a
 * default no-arg constructor for Kafka's class-based deserializer SPI.
 */
public class TicketFormFilledEventDeserializer implements Deserializer<TicketFormFilledEvent> {

    private final ObjectMapper objectMapper;

    /**
     * Constructs a deserializer using the shared default Kafka {@link ObjectMapper} (required by
     * Kafka's class-based {@link Deserializer} SPI).
     */
    public TicketFormFilledEventDeserializer() {
        this(KafkaJsonCodecProvider.objectMapper());
    }

    /**
     * Constructs a deserializer with an explicitly injected {@link ObjectMapper} (e.g. from
     * Spring's application context).
     *
     * @param objectMapper pre-configured Jackson {@link ObjectMapper}
     */
    public TicketFormFilledEventDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // No custom configuration required
    }

    @Override
    public TicketFormFilledEvent deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(data);
            CallId callId = CallId.fromString(requireText(root, "callId"));
            UUID ticketId = UUID.fromString(requireText(root, "ticketId"));
            String passengerName = requireText(root, "passengerName");
            Pnr pnr = new Pnr(requireText(root, "pnr"));
            FlightNumber originalFlightNumber =
                    new FlightNumber(requireText(root, "originalFlightNumber"));
            DisruptionReason disruptionReason =
                    DisruptionReason.valueOf(requireText(root, "disruptionReason"));
            RebookingPreference rebookingPreference =
                    RebookingPreference.valueOf(requireText(root, "rebookingPreference"));
            boolean hotelRequired = root.path("hotelRequired").asBoolean(false);
            boolean specialAssistance = root.path("specialAssistance").asBoolean(false);
            String targetSkillGroup = requireText(root, "targetSkillGroup");
            Instant timestamp = Instant.parse(requireText(root, "timestamp"));

            Map<String, Object> payload = extractPayload(root.path("payload"));

            return new TicketFormFilledEvent(
                    callId,
                    ticketId,
                    passengerName,
                    pnr,
                    originalFlightNumber,
                    disruptionReason,
                    rebookingPreference,
                    hotelRequired,
                    specialAssistance,
                    targetSkillGroup,
                    payload,
                    timestamp);
        } catch (SerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new SerializationException(
                    "Failed to deserialize TicketFormFilledEvent JSON payload", e);
        }
    }

    private static String requireText(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new SerializationException(
                    "Missing or blank required field '"
                            + fieldName
                            + "' in TicketFormFilledEvent JSON");
        }
        return node.asText();
    }

    private static Map<String, Object> extractPayload(JsonNode payloadNode) {
        if (payloadNode == null || !payloadNode.isObject()) {
            return Map.of();
        }
        Map<String, Object> payload = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = payloadNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode valNode = field.getValue();
            if (valNode.isTextual()) {
                payload.put(field.getKey(), valNode.asText());
            } else if (valNode.isInt()) {
                payload.put(field.getKey(), valNode.asInt());
            } else if (valNode.isLong()) {
                payload.put(field.getKey(), valNode.asLong());
            } else if (valNode.isFloatingPointNumber()) {
                payload.put(field.getKey(), valNode.asDouble());
            } else if (valNode.isBoolean()) {
                payload.put(field.getKey(), valNode.asBoolean());
            } else if (!valNode.isNull()) {
                payload.put(field.getKey(), valNode.asText());
            }
        }
        return Map.copyOf(payload);
    }

    @Override
    public void close() {
        // Stateless, no resources to release
    }
}
