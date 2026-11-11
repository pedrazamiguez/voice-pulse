package com.voicepulse.shared.infrastructure.kafka.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.common.errors.SerializationException;
import org.instancio.Instancio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TicketFormFilledEvent JSON Serialization Tests")
class TicketFormFilledEventSerializationTest {

    private static final String TOPIC = "telephony.tickets.intent";
    private static final CallId CALL_ID = CallId.generate();
    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final Pnr PNR = new Pnr("A8F9X2");
    private static final FlightNumber FLIGHT = new FlightNumber("SW-104");
    private static final Instant TIMESTAMP = Instant.parse("2026-11-11T08:30:00Z");

    private TicketFormFilledEventSerializer serializer;
    private TicketFormFilledEventDeserializer deserializer;

    @BeforeEach
    void setUp() {
        ObjectMapper injectedMapper = KafkaJsonCodecProvider.objectMapper();
        serializer = new TicketFormFilledEventSerializer(injectedMapper);
        deserializer = new TicketFormFilledEventDeserializer(injectedMapper);
        serializer.configure(Map.of(), false);
        deserializer.configure(Map.of(), false);
    }

    @AfterEach
    void tearDown() {
        serializer.close();
        deserializer.close();
    }

    @Test
    @DisplayName("Should round-trip serialize and deserialize TicketFormFilledEvent with payload")
    void shouldRoundTripTicketFormFilledEvent() {
        Map<String, Object> payload =
                Map.of(
                        "origin",
                        "CTU",
                        "destination",
                        "MAD",
                        "party_size",
                        2,
                        "voucher_amount",
                        150.5,
                        "baggage_checked",
                        true,
                        "sequence_offset",
                        10000000000L);

        TicketFormFilledEvent original =
                Instancio.of(TicketFormFilledEvent.class)
                        .set(field(TicketFormFilledEvent::callId), CALL_ID)
                        .set(field(TicketFormFilledEvent::ticketId), TICKET_ID)
                        .set(field(TicketFormFilledEvent::passengerName), "Andrés Pedraza Míguez")
                        .set(field(TicketFormFilledEvent::pnr), PNR)
                        .set(field(TicketFormFilledEvent::originalFlightNumber), FLIGHT)
                        .set(
                                field(TicketFormFilledEvent::disruptionReason),
                                DisruptionReason.CANCELED_WEATHER)
                        .set(
                                field(TicketFormFilledEvent::rebookingPreference),
                                RebookingPreference.NEXT_AVAILABLE)
                        .set(field(TicketFormFilledEvent::hotelRequired), true)
                        .set(field(TicketFormFilledEvent::specialAssistance), false)
                        .set(field(TicketFormFilledEvent::targetSkillGroup), "DISRUPTION_DESK_GOLD")
                        .set(field(TicketFormFilledEvent::payload), payload)
                        .set(field(TicketFormFilledEvent::timestamp), TIMESTAMP)
                        .create();

        byte[] bytes = serializer.serialize(TOPIC, original);
        assertThat(bytes).isNotNull().isNotEmpty();

        TicketFormFilledEvent decoded = deserializer.deserialize(TOPIC, bytes);
        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.payload())
                .containsEntry("origin", "CTU")
                .containsEntry("destination", "MAD")
                .containsEntry("party_size", 2)
                .containsEntry("voucher_amount", 150.5)
                .containsEntry("baggage_checked", true)
                .containsEntry("sequence_offset", 10000000000L);
    }

    @Test
    @DisplayName("Should support default no-arg constructor for Kafka SPI")
    void shouldSupportDefaultNoArgConstructor() {
        try (TicketFormFilledEventSerializer defaultSerializer =
                        new TicketFormFilledEventSerializer();
                TicketFormFilledEventDeserializer defaultDeserializer =
                        new TicketFormFilledEventDeserializer()) {
            TicketFormFilledEvent original =
                    Instancio.of(TicketFormFilledEvent.class)
                            .set(field(TicketFormFilledEvent::pnr), PNR)
                            .set(field(TicketFormFilledEvent::originalFlightNumber), FLIGHT)
                            .set(field(TicketFormFilledEvent::payload), Map.of())
                            .create();

            byte[] bytes = defaultSerializer.serialize(TOPIC, original);
            assertThat(defaultDeserializer.deserialize(TOPIC, bytes)).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("Should reject null ObjectMapper in constructor")
    void shouldRejectNullObjectMapper() {
        assertThatThrownBy(() -> new TicketFormFilledEventSerializer(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("objectMapper must not be null");

        assertThatThrownBy(() -> new TicketFormFilledEventDeserializer(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("objectMapper must not be null");
    }

    @Test
    @DisplayName("Should return null when serializing or deserializing null or empty bytes")
    void shouldHandleNullAndEmpty() {
        assertThat(serializer.serialize(TOPIC, null)).isNull();
        assertThat(deserializer.deserialize(TOPIC, null)).isNull();
        assertThat(deserializer.deserialize(TOPIC, new byte[0])).isNull();
    }

    @Test
    @DisplayName("Should reject malformed JSON with SerializationException")
    void shouldRejectMalformedJson() {
        byte[] malformed = "{ malformed json ".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> deserializer.deserialize(TOPIC, malformed))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Failed to deserialize TicketFormFilledEvent JSON payload");
    }

    @Test
    @DisplayName("Should reject JSON missing required fields with SerializationException")
    void shouldRejectMissingRequiredFields() {
        String missingPnrJson =
                "{\"callId\":\""
                        + CALL_ID
                        + "\",\"ticketId\":\""
                        + TICKET_ID
                        + "\",\"passengerName\":\"Andrés\"}";
        byte[] bytes = missingPnrJson.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> deserializer.deserialize(TOPIC, bytes))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Missing or blank required field 'pnr'");
    }
}
