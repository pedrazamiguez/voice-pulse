package com.voicepulse.conversation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.conversation.application.dialog.DialogTurnResult;
import com.voicepulse.conversation.application.dialog.MockLlmConversationProcessor;
import com.voicepulse.conversation.application.registry.ConversationSessionRegistry;
import com.voicepulse.conversation.domain.event.CallStateTransitioned;
import com.voicepulse.conversation.domain.event.TurnCompleted;
import com.voicepulse.conversation.domain.ports.outbound.CallControlDispatcherPort;
import com.voicepulse.conversation.domain.ports.outbound.ConversationStatePublisherPort;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.conversation.domain.vad.BargeInEvaluationResult;
import com.voicepulse.conversation.domain.vad.BargeInEvaluator;
import com.voicepulse.conversation.domain.vad.TurnPhase;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationOrchestratorService Unit Tests")
class ConversationOrchestratorServiceTest {

    @Mock private MockLlmConversationProcessor dialogProcessor;

    @Mock private BargeInEvaluator bargeInEvaluator;

    @Mock private CallControlDispatcherPort callControlDispatcher;

    @Mock private ConversationStatePublisherPort statePublisher;

    @Captor private ArgumentCaptor<CallStateTransitioned> transitionCaptor;

    @Captor private ArgumentCaptor<TurnCompleted> turnCompletedCaptor;

    @Captor private ArgumentCaptor<BargeInInterrupt> interruptCaptor;

    private ConversationSessionRegistry sessionRegistry;
    private ConversationOrchestratorService service;
    private CallId callId;
    private Instant now;

    @BeforeEach
    void setUp() {
        sessionRegistry = new ConversationSessionRegistry();
        service =
                new ConversationOrchestratorService(
                        sessionRegistry,
                        dialogProcessor,
                        bargeInEvaluator,
                        callControlDispatcher,
                        statePublisher);
        callId = CallId.generate();
        now = Instant.now();
    }

    @Test
    @DisplayName("startConversation initializes session in Greeting and publishes transition event")
    void startConversation_initializesAndPublishesGreeting() {
        ConversationSession session = service.startConversation(callId, now);

        assertThat(session.callId()).isEqualTo(callId);
        assertThat(session.state()).isInstanceOf(CallSessionState.Greeting.class);
        assertThat(session.turnPhase()).isEqualTo(TurnPhase.BOT_SPEAKING);

        verify(statePublisher).publishStateTransition(transitionCaptor.capture());
        CallStateTransitioned event = transitionCaptor.getValue();
        assertThat(event.callId()).isEqualTo(callId);
        assertThat(event.newState()).isInstanceOf(CallSessionState.Greeting.class);
    }

    @Test
    @DisplayName(
            "progressTurn coordinates dialogue processor, records turn, and publishes domain"
                    + " events")
    void progressTurn_progressesDialogueAndPublishesEvents() {
        service.startConversation(callId, now);

        CallSessionState.CollectingPassengerDetails targetState =
                new CallSessionState.CollectingPassengerDetails(1);

        when(dialogProcessor.processTurn(any(ConversationSession.class), eq("A8F9X2"), eq(now)))
                .thenAnswer(
                        invocation -> {
                            ConversationSession s = invocation.getArgument(0);
                            s.promptPassengerDetails(1);
                            return new DialogTurnResult(
                                    "Please confirm.", targetState, Optional.empty(), 500_000L);
                        });

        ConversationSession session = service.progressTurn(callId, "A8F9X2", now);

        assertThat(session.state()).isEqualTo(targetState);
        assertThat(session.turnCount()).isEqualTo(1);

        verify(statePublisher).publishTurnCompleted(turnCompletedCaptor.capture());
        TurnCompleted turnEvent = turnCompletedCaptor.getValue();
        assertThat(turnEvent.callId()).isEqualTo(callId);
        assertThat(turnEvent.turnNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "processAudioChunk detects barge-in during bot turn and dispatches BargeInInterrupt")
    void processAudioChunk_botSpeakingAndSpeechDetected_dispatchesInterrupt() {
        ConversationSession session = service.startConversation(callId, now);
        assertThat(session.turnPhase()).isEqualTo(TurnPhase.BOT_SPEAKING);

        byte[] pcmData = new byte[640];
        AudioChunk chunk =
                new AudioChunk(
                        CallSessionId.fromCallId(callId),
                        10L,
                        now,
                        pcmData,
                        AudioFormat.PCM_16KHZ_MONO);

        when(bargeInEvaluator.evaluate(any(byte[].class), eq(true)))
                .thenReturn(BargeInEvaluationResult.triggered(2500.0, 0.95));

        service.processAudioChunk(chunk);

        assertThat(session.turnPhase()).isEqualTo(TurnPhase.LISTENING);
        verify(callControlDispatcher).dispatch(interruptCaptor.capture());
        BargeInInterrupt interrupt = interruptCaptor.getValue();
        assertThat(interrupt.callId()).isEqualTo(callId);
        assertThat(interrupt.sequenceNumber()).isEqualTo(10L);
    }

    @Test
    @DisplayName("processAudioChunk does not trigger barge-in when turn phase is LISTENING")
    void processAudioChunk_listeningPhase_doesNotTriggerBargeIn() {
        ConversationSession session = sessionRegistry.getOrCreate(callId, now);
        // Default turnPhase is LISTENING

        byte[] pcmData = new byte[640];
        AudioChunk chunk =
                new AudioChunk(
                        CallSessionId.fromCallId(callId),
                        1L,
                        now,
                        pcmData,
                        AudioFormat.PCM_16KHZ_MONO);

        service.processAudioChunk(chunk);

        verify(bargeInEvaluator, never()).evaluate(any(byte[].class), eq(true));
        verify(callControlDispatcher, never()).dispatch(any());
    }

    @Test
    @DisplayName("findSession retrieves active session from registry")
    void findSession_returnsSession() {
        assertThat(service.findSession(callId)).isEmpty();

        service.startConversation(callId, now);

        assertThat(service.findSession(callId)).isPresent();
        assertThat(service.findSession(callId).get().callId()).isEqualTo(callId);
    }

    @Test
    @DisplayName("Throws NullPointerException on null parameters")
    void methods_throwOnNullParameters() {
        assertThatThrownBy(() -> service.startConversation(null, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");

        assertThatThrownBy(() -> service.startConversation(callId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("connectedAt");

        assertThatThrownBy(() -> service.progressTurn(null, "hello", now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");

        assertThatThrownBy(() -> service.progressTurn(callId, null, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("utterance");

        assertThatThrownBy(() -> service.progressTurn(callId, "hello", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");

        assertThatThrownBy(() -> service.findSession(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");

        assertThatThrownBy(() -> service.processAudioChunk(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("chunk");
    }
}
