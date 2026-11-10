package com.voicepulse.conversation.domain.ports.outbound;

import com.voicepulse.conversation.domain.event.CallStateTransitioned;
import com.voicepulse.conversation.domain.event.TurnCompleted;

/**
 * Driven (outbound) port for publishing conversational state transitions and turn completions to
 * external streaming topics or telemetry collectors.
 */
public interface ConversationStatePublisherPort {

    /**
     * Publishes a call state transition event.
     *
     * @param event state transition event
     */
    void publishStateTransition(CallStateTransitioned event);

    /**
     * Publishes a completed conversational turn event.
     *
     * @param event turn completed event
     */
    void publishTurnCompleted(TurnCompleted event);
}
