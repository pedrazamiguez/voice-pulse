package com.voicepulse.conversation.domain.event;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;

/**
 * Sealed interface representing pure domain events emitted during conversational lifecycle
 * progression.
 */
public sealed interface ConversationDomainEvent
        permits CallStateTransitioned, TurnCompleted, BargeInDetected {

    /** Identifier of the conversational call session. */
    CallId callId();

    /** UTC timestamp when the domain event occurred. */
    Instant occurredAt();
}
