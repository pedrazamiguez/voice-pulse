package com.voicepulse.routing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JsonbColumnCodec Unit Tests")
class JsonbColumnCodecTest {

    private final ObjectMapper objectMapper = KafkaJsonCodecProvider.objectMapper();
    private final JsonbColumnCodec codec = new JsonbColumnCodec(objectMapper);

    @Test
    @DisplayName("Should serialize and deserialize structured JSONB payload maps")
    void shouldRoundTripStructuredMap() {
        Map<String, Object> source =
                Map.of("departure_airport", "CTU", "party_size", 2, "baggage_checked", true);

        String json = codec.toJson(source);
        Map<String, Object> decoded = codec.fromJson(json);

        assertThat(decoded)
                .containsEntry("departure_airport", "CTU")
                .containsEntry("party_size", 2)
                .containsEntry("baggage_checked", true);
    }

    @Test
    @DisplayName("Should serialize null or empty maps to '{}' and decode blank JSON to empty map")
    void shouldHandleEmptyAndNullPayloads() {
        assertThat(codec.toJson(null)).isEqualTo("{}");
        assertThat(codec.toJson(Map.of())).isEqualTo("{}");
        assertThat(codec.fromJson(null)).isEmpty();
        assertThat(codec.fromJson("   ")).isEmpty();
        assertThat(codec.fromJson("{}")).isEmpty();
    }

    @Test
    @DisplayName("Should wrap malformed JSON into TicketPersistenceException")
    void shouldThrowTicketPersistenceExceptionOnInvalidJson() {
        assertThatThrownBy(() -> codec.fromJson("{invalid-json"))
                .isInstanceOf(TicketPersistenceException.class)
                .hasMessageContaining("deserialize");
    }
}
