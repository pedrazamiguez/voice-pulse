package com.voicepulse.conversation.domain.model;

import java.util.Objects;

/**
 * Immutable domain record representing real-time operator availability and queue depth for a target
 * skill group queried via {@code mcp-telephony-routing} (RFC-0001 §4.4 & RFC-0003 §4.3).
 */
public record OperatorQueueStatus(
        String department, int availableOperators, int estimatedWaitSeconds, int queuePosition) {

    public OperatorQueueStatus {
        Objects.requireNonNull(department, "department must not be null");
        if (department.isBlank()) {
            throw new IllegalArgumentException("department must not be blank");
        }
        if (availableOperators < 0) {
            throw new IllegalArgumentException(
                    "availableOperators must be >= 0: " + availableOperators);
        }
        if (estimatedWaitSeconds < 0) {
            throw new IllegalArgumentException(
                    "estimatedWaitSeconds must be >= 0: " + estimatedWaitSeconds);
        }
        if (queuePosition < 0) {
            throw new IllegalArgumentException("queuePosition must be >= 0: " + queuePosition);
        }
    }
}
