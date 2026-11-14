package com.voicepulse.conversation.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;

/** OpenAI/Ollama-compatible function schema DTO. */
public record ChatFunctionDefinitionDto(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("parameters") Map<String, Object> parameters) {

    public ChatFunctionDefinitionDto {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(description, "description cannot be null");
        parameters = (parameters == null) ? Map.of() : Map.copyOf(parameters);
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.copyOf(parameters);
    }
}
