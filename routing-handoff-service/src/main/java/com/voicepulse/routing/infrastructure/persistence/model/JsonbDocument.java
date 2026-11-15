package com.voicepulse.routing.infrastructure.persistence.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable value-object wrapper for PostgreSQL {@code JSONB} columns ({@code metadata}, {@code
 * payload}, {@code details}).
 *
 * <p>Wrapping {@code Map<String, Object>} prevents Spring Data JDBC from interpreting JSONB columns
 * as relational 1:N child table mappings while preserving insertion order and null-safe key lookup.
 */
public record JsonbDocument(Map<String, Object> entries) {

    public JsonbDocument {
        entries = immutableCopy(entries);
    }

    @Override
    public Map<String, Object> entries() {
        return immutableCopy(entries);
    }

    /** Returns an empty {@link JsonbDocument}. */
    public static JsonbDocument empty() {
        return new JsonbDocument(Map.of());
    }

    /** Wraps a nullable map into an immutable {@link JsonbDocument}. */
    public static JsonbDocument of(Map<String, Object> entries) {
        return (entries == null || entries.isEmpty()) ? empty() : new JsonbDocument(entries);
    }

    /**
     * Merges {@code incoming} entries onto this document, matching PostgreSQL {@code existing ||
     * incoming} semantics where incoming keys override matching existing keys.
     */
    public JsonbDocument mergeWith(JsonbDocument incoming) {
        if (incoming == null || incoming.entries.isEmpty()) {
            return this;
        }
        if (entries.isEmpty()) {
            return incoming;
        }
        Map<String, Object> merged = new LinkedHashMap<>(entries);
        merged.putAll(incoming.entries);
        return new JsonbDocument(merged);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
