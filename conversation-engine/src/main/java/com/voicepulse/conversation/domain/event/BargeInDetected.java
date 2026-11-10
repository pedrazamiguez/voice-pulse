package com.voicepulse.conversation.domain.event;

import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;

/** Domain event emitted when caller speech interrupts active bot playback. */
public record BargeInDetected(CallId callId, CallSessionState currentState, Instant occurredAt)
        implements ConversationDomainEvent {

    public BargeInDetected {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(currentState, "currentState cannot be null");
        Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
    }
}
