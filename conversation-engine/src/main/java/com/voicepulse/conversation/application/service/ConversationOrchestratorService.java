package com.voicepulse.conversation.application.service;

import com.voicepulse.conversation.application.registry.ConversationSessionRegistry;
import com.voicepulse.conversation.domain.event.BargeInDetected;
import com.voicepulse.conversation.domain.event.CallStateTransitioned;
import com.voicepulse.conversation.domain.event.ConversationDomainEvent;
import com.voicepulse.conversation.domain.event.TurnCompleted;
import com.voicepulse.conversation.domain.model.DialogTurnResult;
import com.voicepulse.conversation.domain.ports.inbound.ProcessAudioChunkUseCase;
import com.voicepulse.conversation.domain.ports.inbound.ProgressConversationUseCase;
import com.voicepulse.conversation.domain.ports.outbound.CallControlDispatcherPort;
import com.voicepulse.conversation.domain.ports.outbound.ConversationDialogProcessorPort;
import com.voicepulse.conversation.domain.ports.outbound.ConversationStatePublisherPort;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.conversation.domain.vad.BargeInEvaluationResult;
import com.voicepulse.conversation.domain.vad.BargeInEvaluator;
import com.voicepulse.conversation.domain.vad.TrailingSilenceEvaluator;
import com.voicepulse.conversation.domain.vad.TurnPhase;
import com.voicepulse.conversation.domain.vad.VadEvaluationResult;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Driving application service orchestrating conversational telephony intelligence, VAD analysis,
 * finite state transitions, and mock LLM dialogue turns.
 *
 * <p>Implements {@link ProcessAudioChunkUseCase} and {@link ProgressConversationUseCase}, cleanly
 * superseding fallback no-op beans in the Spring context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationOrchestratorService
        implements ProcessAudioChunkUseCase, ProgressConversationUseCase {

    private static final String INITIAL_GREETING_PROMPT =
            "Welcome to the Airline Disruption Hotline. To quickly assist you, please state your"
                    + " six-character booking reference or PNR.";

    private final @NonNull ConversationSessionRegistry sessionRegistry;
    private final @NonNull ConversationDialogProcessorPort dialogProcessor;
    private final @NonNull BargeInEvaluator bargeInEvaluator;
    private final @NonNull CallControlDispatcherPort callControlDispatcher;
    private final @NonNull ConversationStatePublisherPort statePublisher;

    @Override
    public ConversationSession startConversation(CallId callId, Instant connectedAt) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(connectedAt, "connectedAt cannot be null");

        log.info("Starting conversation session: callId={}, connectedAt={}", callId, connectedAt);
        ConversationSession session = sessionRegistry.getOrCreate(callId, connectedAt);

        if (session.state() instanceof CallSessionState.Connected) {
            long startNanos = System.nanoTime();
            session.greet(connectedAt);
            session.recordBotPrompt(INITIAL_GREETING_PROMPT, System.nanoTime() - startNanos);
            session.startBotTurn(connectedAt);
        }

        drainAndPublishEvents(session);
        return session;
    }

    @Override
    public ConversationSession progressTurn(CallId callId, String utterance, Instant timestamp) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(utterance, "utterance cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        log.debug(
                "Progressing turn: callId={}, utterance={}, timestamp={}",
                callId,
                utterance,
                timestamp);

        ConversationSession session = sessionRegistry.getOrCreate(callId, timestamp);

        DialogTurnResult turnResult = dialogProcessor.processTurn(session, utterance, timestamp);
        session.recordBotPrompt(turnResult.responseText(), turnResult.executionDurationNanos());
        session.recordTurn(timestamp);

        CallSessionState resultingState = turnResult.targetState();
        if (!(resultingState instanceof CallSessionState.HandoffReady)
                && !(resultingState instanceof CallSessionState.Transferred)
                && !(resultingState instanceof CallSessionState.Terminated)) {
            session.startBotTurn(timestamp);
        }

        drainAndPublishEvents(session);
        return session;
    }

    @Override
    public Optional<ConversationSession> findSession(CallId callId) {
        Objects.requireNonNull(callId, "callId cannot be null");
        return sessionRegistry.find(callId);
    }

    @Override
    public void processAudioChunk(AudioChunk chunk) {
        Objects.requireNonNull(chunk, "chunk cannot be null");

        CallId callId = chunk.sessionId().toCallId();
        ConversationSession session = sessionRegistry.getOrCreate(callId, chunk.timestamp());

        if (session.isTerminated()) {
            return;
        }

        // 1. Evaluate Barge-in if synthetic playback is active
        if (session.turnPhase() == TurnPhase.BOT_SPEAKING) {
            BargeInEvaluationResult bargeInResult =
                    bargeInEvaluator.evaluate(chunk.pcmData(), true);
            if (bargeInResult.bargeInDetected()) {
                log.info(
                        "Caller barge-in detected during bot turn: callId={}, seq={}, rms={}",
                        callId,
                        chunk.sequenceNumber(),
                        bargeInResult.rmsEnergy());

                session.recordBargeIn(chunk.timestamp());
                callControlDispatcher.dispatch(
                        new BargeInInterrupt(callId, chunk.sequenceNumber(), chunk.timestamp()));
                drainAndPublishEvents(session);
                return;
            }
        }

        // 2. Continuous VAD analysis & Turn boundary detection
        TrailingSilenceEvaluator trailingSilenceEvaluator =
                sessionRegistry.getOrCreateTrailingSilenceEvaluator(callId);
        VadEvaluationResult vadResult = trailingSilenceEvaluator.evaluate(chunk.pcmData());

        if (vadResult.turnBoundaryDetected()) {
            log.debug(
                    "VAD turn boundary detected: callId={}, silenceMs={}",
                    callId,
                    vadResult.contiguousSilenceMs());
            trailingSilenceEvaluator.reset();
        }

        drainAndPublishEvents(session);
    }

    private void drainAndPublishEvents(ConversationSession session) {
        List<ConversationDomainEvent> domainEvents = session.pollDomainEvents();
        for (ConversationDomainEvent event : domainEvents) {
            switch (event) {
                case CallStateTransitioned transitioned ->
                        statePublisher.publishStateTransition(transitioned);
                case TurnCompleted turnCompleted ->
                        statePublisher.publishTurnCompleted(turnCompleted);
                case BargeInDetected bargeIn ->
                        log.debug(
                                "Barge-in recorded for session: callId={}, at={}",
                                bargeIn.callId(),
                                bargeIn.occurredAt());
            }
        }
    }
}
