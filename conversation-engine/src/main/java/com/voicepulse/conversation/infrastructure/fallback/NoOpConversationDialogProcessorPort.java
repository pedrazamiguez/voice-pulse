package com.voicepulse.conversation.infrastructure.fallback;

import com.voicepulse.conversation.domain.model.DialogTurnResult;
import com.voicepulse.conversation.domain.ports.outbound.ConversationDialogProcessorPort;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op implementation of {@link ConversationDialogProcessorPort} (Null Object Pattern).
 *
 * <p>Registered as a Spring {@link Fallback} bean so it is automatically superseded when either the
 * {@code mock} or {@code live} conversational processor bean is active.
 */
@Slf4j
@Fallback
@Component
public class NoOpConversationDialogProcessorPort implements ConversationDialogProcessorPort {

    private static final String FALLBACK_RESPONSE =
            "Thank you for calling the SkyWay Airlines hotline. Connecting you to an agent now.";

    @Override
    public DialogTurnResult processTurn(
            ConversationSession session, String utterance, Instant timestamp) {
        Objects.requireNonNull(session, "session cannot be null");
        Objects.requireNonNull(utterance, "utterance cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        log.debug(
                "No-op ConversationDialogProcessorPort invoked: callId={}, state={}",
                session.callId(),
                session.state());
        return new DialogTurnResult(FALLBACK_RESPONSE, session.state(), Optional.empty(), 0L);
    }
}
