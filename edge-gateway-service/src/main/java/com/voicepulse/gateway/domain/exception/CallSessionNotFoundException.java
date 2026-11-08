package com.voicepulse.gateway.domain.exception;

import com.voicepulse.shared.model.CallSessionId;

/** Thrown when an operation is requested on a non-existent call session. */
public class CallSessionNotFoundException extends RuntimeException {

    public CallSessionNotFoundException(CallSessionId sessionId) {
        super("Session not found: " + sessionId);
    }

    public CallSessionNotFoundException(String message) {
        super(message);
    }
}
