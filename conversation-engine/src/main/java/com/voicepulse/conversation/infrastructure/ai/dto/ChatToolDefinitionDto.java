package com.voicepulse.conversation.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** OpenAI/Ollama-compatible tool definition wrapper DTO. */
public record ChatToolDefinitionDto(
        @JsonProperty("type") String type,
        @JsonProperty("function") ChatFunctionDefinitionDto function) {

    public ChatToolDefinitionDto {
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(function, "function cannot be null");
    }

    public static ChatToolDefinitionDto functionTool(ChatFunctionDefinitionDto function) {
        return new ChatToolDefinitionDto("function", function);
    }
}
