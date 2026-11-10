package com.voicepulse.conversation.domain.exception;

/**
 * Domain exception thrown when an invalid conversational state transition is attempted on a {@link
 * com.voicepulse.conversation.domain.statemachine.ConversationSession}.
 *
 * <p>Pure domain exception with zero framework dependencies.
 */
public class IllegalConversationStateException extends IllegalStateException {

    public IllegalConversationStateException(String message) {
        super(message);
    }

    public IllegalConversationStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
