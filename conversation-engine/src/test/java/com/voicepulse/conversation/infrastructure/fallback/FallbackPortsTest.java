package com.voicepulse.conversation.infrastructure.fallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.voicepulse.conversation.domain.event.CallStateTransitioned;
import com.voicepulse.conversation.domain.event.TurnCompleted;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Null Object Fallback Ports Tests")
class FallbackPortsTest {

    @Test
    @DisplayName("NoOpProgressConversationUseCase operates as a safe Null Object")
    void progressConversationUseCaseFallback() {
        NoOpProgressConversationUseCase useCase = new NoOpProgressConversationUseCase();
        CallId callId = CallId.generate();
        Instant now = Instant.now();

        ConversationSession session1 = useCase.startConversation(callId, now);
        assertThat(session1.callId()).isEqualTo(callId);

        ConversationSession session2 = useCase.progressTurn(callId, "hello", now);
        assertThat(session2.callId()).isEqualTo(callId);

        Optional<ConversationSession> sessionOpt = useCase.findSession(callId);
        assertThat(sessionOpt).isEmpty();
    }

    @Test
    @DisplayName("NoOpProcessCallControlUseCase operates as a safe Null Object")
    void processCallControlUseCaseFallback() {
        NoOpProcessCallControlUseCase useCase = new NoOpProcessCallControlUseCase();
        CallId callId = CallId.generate();
        Instant now = Instant.now();

        assertThatCode(
                        () -> {
                            useCase.handleCallStarted(callId, now);
                            useCase.handleBargeIn(callId, now);
                            useCase.handleHangup(callId);
                        })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NoOpConversationStatePublisherPort operates as a safe Null Object")
    void conversationStatePublisherPortFallback() {
        NoOpConversationStatePublisherPort port = new NoOpConversationStatePublisherPort();
        CallId callId = CallId.generate();
        Instant now = Instant.now();
        CallSessionState state = new CallSessionState.Connected(now);

        assertThatCode(
                        () -> {
                            port.publishStateTransition(
                                    new CallStateTransitioned(
                                            callId,
                                            state,
                                            new CallSessionState.Greeting(now),
                                            now));
                            port.publishTurnCompleted(new TurnCompleted(callId, 1, state, now));
                        })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NoOpCallControlDispatcherPort operates as a safe Null Object")
    void callControlDispatcherPortFallback() {
        NoOpCallControlDispatcherPort port = new NoOpCallControlDispatcherPort();
        CallId callId = CallId.generate();
        Instant now = Instant.now();

        assertThatCode(() -> port.dispatch(new BargeInInterrupt(callId, 1L, now)))
                .doesNotThrowAnyException();
    }
}
