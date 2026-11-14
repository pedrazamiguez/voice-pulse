package com.voicepulse.conversation.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/** OpenAI/Ollama-compatible chat completion request DTO with optional function tool definitions. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionRequest(
        @JsonProperty("model") String model,
        @JsonProperty("messages") List<ChatMessageDto> messages,
        @JsonProperty("tools") List<ChatToolDefinitionDto> tools,
        @JsonProperty("temperature") double temperature,
        @JsonProperty("stream") boolean stream) {

    public ChatCompletionRequest {
        Objects.requireNonNull(model, "model cannot be null");
        messages = (messages == null) ? List.of() : List.copyOf(messages);
        tools = (tools == null) ? null : List.copyOf(tools);
    }

    @Override
    public List<ChatMessageDto> messages() {
        return List.copyOf(messages);
    }

    @Override
    public List<ChatToolDefinitionDto> tools() {
        return (tools == null) ? null : List.copyOf(tools);
    }
}
