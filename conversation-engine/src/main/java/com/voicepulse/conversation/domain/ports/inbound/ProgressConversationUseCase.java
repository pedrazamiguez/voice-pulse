package com.voicepulse.conversation.domain.ports.inbound;

import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Optional;

/**
 * Driving (inbound) port for initializing and progressing conversational turns in the conversation
 * engine.
 */
public interface ProgressConversationUseCase {

    /**
     * Initializes or connects a new conversation session.
     *
     * @param callId unique call identifier
     * @param connectedAt timestamp of connection
     * @return initialized conversation session
     */
    ConversationSession startConversation(CallId callId, Instant connectedAt);

    /**
     * Processes a completed user utterance or conversational turn for the given call session.
     *
     * @param callId unique call identifier
     * @param utterance transcribed user speech or intent input
     * @param timestamp timestamp of the turn
     * @return updated conversation session
     */
    ConversationSession progressTurn(CallId callId, String utterance, Instant timestamp);

    /**
     * Retrieves the active conversation session if present.
     *
     * @param callId unique call identifier
     * @return optional containing the active session, or empty if not found
     */
    Optional<ConversationSession> findSession(CallId callId);
}
