package com.voicepulse.routing.infrastructure.ws.dto;

import java.time.Instant;
import java.util.Objects;

/** Immutable DTO representing a single conversational transcript turn for operator handoff view. */
public record TranscriptTurnDto(String speaker, String text, Instant timestamp) {

    public TranscriptTurnDto {
        Objects.requireNonNull(speaker, "speaker cannot be null");
        Objects.requireNonNull(text, "text cannot be null");
        speaker = speaker.trim();
        text = text.trim();
    }
}
