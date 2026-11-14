package com.voicepulse.conversation.domain.model;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain record representing the confirmation receipt returned by {@code
 * mcp-telephony-routing} upon transferring a call session to a human operator queue (RFC-0003
 * §4.3).
 */
public record OperatorHandoffReceipt(
        CallId callId,
        UUID ticketId,
        String department,
        String status,
        int estimatedWaitSeconds,
        int queuePosition,
        Instant dispatchedAt) {

    public OperatorHandoffReceipt {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(department, "department must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(dispatchedAt, "dispatchedAt must not be null");

        if (department.isBlank()) {
            throw new IllegalArgumentException("department must not be blank");
        }
        if (status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
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
