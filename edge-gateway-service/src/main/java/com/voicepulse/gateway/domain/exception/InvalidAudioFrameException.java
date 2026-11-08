package com.voicepulse.gateway.domain.exception;

/**
 * Thrown when an audio frame violates acoustic format, size boundaries, or monotonic sequencing.
 */
public class InvalidAudioFrameException extends RuntimeException {

    public InvalidAudioFrameException(String message) {
        super(message);
    }

    public InvalidAudioFrameException(String message, Throwable cause) {
        super(message, cause);
    }
}
