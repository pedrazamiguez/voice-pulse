package com.voicepulse.conversation.domain.event;

import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;

/** Domain event emitted when a conversational session transitions between states. */
public record CallStateTransitioned(
        CallId callId,
        CallSessionState previousState,
        CallSessionState newState,
        Instant occurredAt)
        implements ConversationDomainEvent {

    public CallStateTransitioned {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(previousState, "previousState cannot be null");
        Objects.requireNonNull(newState, "newState cannot be null");
        Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
    }
}
