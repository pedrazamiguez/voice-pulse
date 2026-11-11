package com.voicepulse.conversation.infrastructure.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Transport request DTO for the {@code lookup_customer} tool on {@code mcp-crm-server} (RFC-0003
 * §4.1).
 */
public record McpCustomerLookupRequestDto(@JsonProperty("pnr") String pnr) {

    public McpCustomerLookupRequestDto {
        Objects.requireNonNull(pnr, "pnr must not be null");
        if (pnr.isBlank()) {
            throw new IllegalArgumentException("pnr must not be blank");
        }
    }
}
