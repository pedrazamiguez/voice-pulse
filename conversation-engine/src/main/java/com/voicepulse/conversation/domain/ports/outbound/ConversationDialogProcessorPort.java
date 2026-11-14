package com.voicepulse.conversation.domain.ports.outbound;

import com.voicepulse.conversation.domain.model.DialogTurnResult;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import java.time.Instant;

/**
 * Driven (outbound) strategy port for synthesizing conversational turns across the dual processing
 * modes ({@code mock} simulation vs. {@code live} LLM tool-calling) per RFC-0001 §4.4 and RFC-0002
 * §9.3.
 */
public interface ConversationDialogProcessorPort {

    /**
     * Processes a single conversational turn for the given session and transcribed caller
     * utterance.
     *
     * @param session active conversation session aggregate root
     * @param utterance transcribed caller speech input
     * @param timestamp timestamp of the turn
     * @return immutable turn result with synthesized response, target state, and optional ticket
     */
    DialogTurnResult processTurn(ConversationSession session, String utterance, Instant timestamp);
}
