package com.voicepulse.gateway.infrastructure.ws;

import com.voicepulse.shared.model.CallSessionId;
import java.util.Objects;

/** Parsed operator identifier and target call session identifier extracted from a WebSocket URI. */
public record OperatorAudioTarget(String operatorId, CallSessionId sessionId) {

    public OperatorAudioTarget {
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operatorId cannot be null or blank");
        }
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
    }
}
