package com.voicepulse.conversation.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Tool argument DTO for the {@code lookup_customer} LLM function call. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LookupCustomerToolArgs(@JsonProperty("pnr") String pnr) {}
