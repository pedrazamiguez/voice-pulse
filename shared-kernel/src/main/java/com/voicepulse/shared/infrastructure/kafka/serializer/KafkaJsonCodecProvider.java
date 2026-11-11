package com.voicepulse.shared.infrastructure.kafka.serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared thread-safe {@link ObjectMapper} provider for Kafka {@code Serializer} and {@code
 * Deserializer} implementations when instantiated via Kafka's default no-arg constructor SPI.
 *
 * <p>In Spring-managed runtime services, prefer injecting the application's {@link ObjectMapper}
 * bean via constructor injection into the serializer or deserializer.
 */
public final class KafkaJsonCodecProvider {

    private static final ObjectMapper SHARED_MAPPER =
            JsonMapper.builder()
                    .addModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();

    private KafkaJsonCodecProvider() {
        // Utility holder class
    }

    /**
     * Returns an isolated, pre-configured {@link ObjectMapper} instance sharing underlying Jackson
     * configuration caches.
     *
     * @return pre-configured {@link ObjectMapper}
     */
    public static ObjectMapper objectMapper() {
        return SHARED_MAPPER.copy();
    }
}
