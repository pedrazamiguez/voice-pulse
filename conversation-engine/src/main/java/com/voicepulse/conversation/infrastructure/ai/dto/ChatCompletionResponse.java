package com.voicepulse.conversation.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** OpenAI/Ollama-compatible chat completion response DTO. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionResponse(
        @JsonProperty("id") String id, @JsonProperty("choices") List<ChatChoiceDto> choices) {

    public ChatCompletionResponse {
        choices = (choices == null) ? List.of() : List.copyOf(choices);
    }

    @Override
    public List<ChatChoiceDto> choices() {
        return List.copyOf(choices);
    }
}
