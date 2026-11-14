package com.voicepulse.conversation.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** OpenAI/Ollama-compatible tool call invocation DTO emitted by assistant responses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatToolCallDto(
        @JsonProperty("id") String id,
        @JsonProperty("type") String type,
        @JsonProperty("function") ChatFunctionCallDto function) {}
