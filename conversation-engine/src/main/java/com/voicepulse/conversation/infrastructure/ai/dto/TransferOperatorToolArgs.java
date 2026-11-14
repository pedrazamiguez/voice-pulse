package com.voicepulse.conversation.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Tool argument DTO for the {@code transfer_to_human_operator} LLM function call. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferOperatorToolArgs(@JsonProperty("department") String department) {}
