package com.voicepulse.gateway.domain.exception;

/** Thrown when an illegal lifecycle transition or operation occurs on a call session. */
public class IllegalSessionStateException extends RuntimeException {

    public IllegalSessionStateException(String message) {
        super(message);
    }

    public IllegalSessionStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
