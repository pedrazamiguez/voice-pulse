package com.voicepulse.gateway.domain.service;

import com.voicepulse.gateway.domain.exception.CallSessionNotFoundException;
import com.voicepulse.gateway.domain.model.AudioFrameValidator;
import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.gateway.domain.model.DisconnectReason;
import com.voicepulse.gateway.domain.ports.inbound.IngestAudioChunkUseCase;
import com.voicepulse.gateway.domain.ports.inbound.ManageCallSessionUseCase;
import com.voicepulse.gateway.domain.ports.outbound.AudioFramePublisherPort;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure domain orchestration service implementing inbound telephony use cases.
 *
 * <p>Coordinates the {@link CallSession} aggregate, validates audio chunks, updates registry state,
 * and dispatches outbound audio frames with zero framework contamination.
 */
public class CallSessionService implements IngestAudioChunkUseCase, ManageCallSessionUseCase {

    private final SessionRegistryPort sessionRegistry;
    private final AudioFramePublisherPort audioFramePublisher;

    public CallSessionService(
            SessionRegistryPort sessionRegistry, AudioFramePublisherPort audioFramePublisher) {
        this.sessionRegistry =
                Objects.requireNonNull(sessionRegistry, "sessionRegistry cannot be null");
        this.audioFramePublisher =
                Objects.requireNonNull(audioFramePublisher, "audioFramePublisher cannot be null");
    }

    @Override
    public CallSession startSession(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        CallSession session = CallSession.start(sessionId, Instant.now());
        sessionRegistry.save(session);
        return session;
    }

    @Override
    public void ingestAudioChunk(AudioChunk chunk) {
        Objects.requireNonNull(chunk, "chunk cannot be null");
        AudioFrameValidator.validate(chunk);

        CallSession session =
                sessionRegistry
                        .findById(chunk.sessionId())
                        .orElseThrow(() -> new CallSessionNotFoundException(chunk.sessionId()));

        session.acceptChunk(chunk);
        sessionRegistry.save(session);

        audioFramePublisher.publishAudioChunk(chunk);
    }

    @Override
    public void terminateSession(CallSessionId sessionId, DisconnectReason reason) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");

        CallSession session =
                sessionRegistry
                        .findById(sessionId)
                        .orElseThrow(() -> new CallSessionNotFoundException(sessionId));

        session.terminate(reason, Instant.now());
        sessionRegistry.save(session);
    }

    @Override
    public Optional<CallSession> getSession(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        return sessionRegistry.findById(sessionId);
    }
}
