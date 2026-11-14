package com.voicepulse.conversation.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Tool argument DTO for the {@code finalize_ticket} LLM function call. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinalizeTicketToolArgs(
        @JsonProperty("rebookingPreference") String rebookingPreference,
        @JsonProperty("hotelRequired") boolean hotelRequired,
        @JsonProperty("specialAssistance") boolean specialAssistance) {}
