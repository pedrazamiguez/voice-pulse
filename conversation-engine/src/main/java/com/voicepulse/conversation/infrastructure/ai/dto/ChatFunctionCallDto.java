package com.voicepulse.conversation.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** OpenAI/Ollama-compatible function call name and serialized JSON arguments DTO. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatFunctionCallDto(
        @JsonProperty("name") String name, @JsonProperty("arguments") String arguments) {}
