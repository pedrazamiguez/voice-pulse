package com.voicepulse.conversation.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/**
 * OpenAI/Ollama-compatible chat message DTO representing system, user, assistant, or tool turns.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessageDto(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content,
        @JsonProperty("tool_calls") List<ChatToolCallDto> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonProperty("name") String name) {

    public ChatMessageDto {
        Objects.requireNonNull(role, "role cannot be null");
        toolCalls = (toolCalls == null) ? null : List.copyOf(toolCalls);
    }

    @Override
    public List<ChatToolCallDto> toolCalls() {
        return (toolCalls == null) ? null : List.copyOf(toolCalls);
    }

    public static ChatMessageDto system(String content) {
        return new ChatMessageDto("system", content, null, null, null);
    }

    public static ChatMessageDto user(String content) {
        return new ChatMessageDto("user", content, null, null, null);
    }

    public static ChatMessageDto assistantToolCalls(
            String content, List<ChatToolCallDto> toolCalls) {
        return new ChatMessageDto("assistant", content, toolCalls, null, null);
    }

    public static ChatMessageDto toolResult(String toolCallId, String name, String content) {
        return new ChatMessageDto("tool", content, null, toolCallId, name);
    }
}
