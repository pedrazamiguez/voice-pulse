package com.voicepulse.conversation.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** OpenAI/Ollama-compatible choice entry DTO inside {@link ChatCompletionResponse}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatChoiceDto(
        @JsonProperty("index") int index,
        @JsonProperty("message") ChatMessageDto message,
        @JsonProperty("finish_reason") String finishReason) {}
