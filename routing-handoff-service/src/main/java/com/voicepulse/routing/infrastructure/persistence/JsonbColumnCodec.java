package com.voicepulse.routing.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Cohesive JSONB column codec converting {@code Map<String, Object>} structures to/from JSON
 * strings using the application-managed Jackson {@link ObjectMapper}.
 */
@Component
@RequiredArgsConstructor
public class JsonbColumnCodec {

    private static final String EMPTY_JSON_OBJECT = "{}";

    private final @NonNull ObjectMapper objectMapper;

    /**
     * Serializes a structured map to a JSON string suitable for PostgreSQL {@code JSONB} binding.
     *
     * @param payload structured key-value map (nullable or empty)
     * @return JSON object string
     */
    public String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return EMPTY_JSON_OBJECT;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new TicketPersistenceException("Failed to serialize JSONB column payload", ex);
        }
    }

    /**
     * Deserializes a PostgreSQL {@code JSONB} text value into an unmodifiable map.
     *
     * @param json raw JSON string from JDBC {@code ResultSet}
     * @return immutable map representation (never null)
     */
    public Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JavaType mapType =
                    objectMapper
                            .getTypeFactory()
                            .constructMapType(LinkedHashMap.class, String.class, Object.class);
            Map<String, Object> parsed = objectMapper.readValue(json, mapType);
            if (parsed == null || parsed.isEmpty()) {
                return Map.of();
            }
            return Collections.unmodifiableMap(parsed);
        } catch (JsonProcessingException ex) {
            throw new TicketPersistenceException("Failed to deserialize JSONB column payload", ex);
        }
    }
}
