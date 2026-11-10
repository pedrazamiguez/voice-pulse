package com.voicepulse.conversation.domain.event;

import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;

/** Domain event emitted when a conversational turn is completed. */
public record TurnCompleted(
        CallId callId, int turnNumber, CallSessionState currentState, Instant occurredAt)
        implements ConversationDomainEvent {

    public TurnCompleted {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(currentState, "currentState cannot be null");
        Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
        if (turnNumber < 1) {
            throw new IllegalArgumentException("turnNumber must be >= 1: " + turnNumber);
        }
    }
}
