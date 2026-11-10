package com.voicepulse.conversation.infrastructure.fallback;

import com.voicepulse.conversation.domain.event.CallStateTransitioned;
import com.voicepulse.conversation.domain.event.TurnCompleted;
import com.voicepulse.conversation.domain.ports.outbound.ConversationStatePublisherPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op implementation of {@link ConversationStatePublisherPort} (Null Object Pattern).
 *
 * <p>Registered as a Spring {@link Fallback} bean so that it is automatically superseded once a
 * concrete Kafka state publisher adapter is registered.
 */
@Slf4j
@Fallback
@Component
public class NoOpConversationStatePublisherPort implements ConversationStatePublisherPort {

    @Override
    public void publishStateTransition(CallStateTransitioned event) {
        log.debug(
                "No-op publishStateTransition: callId={}, from={}, to={}",
                event.callId(),
                event.previousState(),
                event.newState());
    }

    @Override
    public void publishTurnCompleted(TurnCompleted event) {
        log.debug(
                "No-op publishTurnCompleted: callId={}, turnNumber={}, state={}",
                event.callId(),
                event.turnNumber(),
                event.currentState());
    }
}
