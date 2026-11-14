package com.voicepulse.conversation.infrastructure.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Transport request DTO for the {@code check_queue_status} tool on {@code mcp-telephony-routing}
 * (RFC-0001 §4.4).
 */
public record McpQueueStatusRequestDto(@JsonProperty("department") String department) {

    public McpQueueStatusRequestDto {
        Objects.requireNonNull(department, "department must not be null");
        if (department.isBlank()) {
            throw new IllegalArgumentException("department must not be blank");
        }
    }
}
