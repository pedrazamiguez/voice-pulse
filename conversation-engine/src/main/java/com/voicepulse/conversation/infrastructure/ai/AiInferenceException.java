package com.voicepulse.conversation.infrastructure.ai;

/**
 * Exception raised when live LLM chat completion or structured tool invocation fails due to
 * transport, timeout, HTTP status, or malformed payload errors.
 */
public class AiInferenceException extends RuntimeException {

    public AiInferenceException(String message) {
        super(message);
    }

    public AiInferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
