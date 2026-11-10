package com.voicepulse.conversation.infrastructure.fallback;

import com.voicepulse.conversation.domain.ports.inbound.ProgressConversationUseCase;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op implementation of {@link ProgressConversationUseCase} (Null Object Pattern).
 *
 * <p>Registered as a Spring {@link Fallback} bean so that it is automatically superseded once a
 * concrete conversational orchestrator is registered.
 */
@Slf4j
@Fallback
@Component
public class NoOpProgressConversationUseCase implements ProgressConversationUseCase {

    @Override
    public ConversationSession startConversation(CallId callId, Instant connectedAt) {
        log.debug("No-op startConversation: callId={}, connectedAt={}", callId, connectedAt);
        return ConversationSession.start(callId, connectedAt);
    }

    @Override
    public ConversationSession progressTurn(CallId callId, String utterance, Instant timestamp) {
        log.debug(
                "No-op progressTurn: callId={}, utterance={}, timestamp={}",
                callId,
                utterance,
                timestamp);
        return ConversationSession.start(callId, timestamp);
    }

    @Override
    public Optional<ConversationSession> findSession(CallId callId) {
        log.debug("No-op findSession: callId={}", callId);
        return Optional.empty();
    }
}
