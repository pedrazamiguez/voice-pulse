package com.voicepulse.conversation.application.dialog;

import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable execution result produced by {@link MockLlmConversationProcessor} for a single
 * conversational turn.
 *
 * <p>Captures the synthesized bot response, resulting conversational state, optional assembled
 * ticket, and deterministic execution duration.
 */
public record DialogTurnResult(
        String responseText,
        CallSessionState targetState,
        Optional<AirlineDisruptionTicket> disruptionTicket,
        long executionDurationNanos) {

    public DialogTurnResult {
        Objects.requireNonNull(responseText, "responseText cannot be null");
        Objects.requireNonNull(targetState, "targetState cannot be null");
        Objects.requireNonNull(disruptionTicket, "disruptionTicket cannot be null");
        if (executionDurationNanos < 0) {
            throw new IllegalArgumentException(
                    "executionDurationNanos cannot be negative: " + executionDurationNanos);
        }
    }
}
