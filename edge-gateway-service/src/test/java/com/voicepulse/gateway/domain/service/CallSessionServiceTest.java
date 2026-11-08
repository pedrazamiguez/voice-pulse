package com.voicepulse.gateway.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.gateway.domain.exception.CallSessionNotFoundException;
import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.gateway.domain.model.CallSessionState;
import com.voicepulse.gateway.domain.model.DisconnectReason;
import com.voicepulse.gateway.domain.ports.outbound.AudioFramePublisherPort;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallSessionServiceTest {

    private InMemorySessionRegistry registry;
    private RecordingAudioFramePublisher publisher;
    private CallSessionService service;

    @BeforeEach
    void setUp() {
        registry = new InMemorySessionRegistry();
        publisher = new RecordingAudioFramePublisher();
        service = new CallSessionService(registry, publisher);
    }

    @Test
    @DisplayName("startSession creates, registers, and returns active CallSession")
    void startSessionRegisters() {
        CallSessionId sessionId = CallSessionId.generate();

        CallSession session = service.startSession(sessionId);

        assertThat(session).isNotNull();
        assertThat(session.id()).isEqualTo(sessionId);
        assertThat(registry.findById(sessionId)).contains(session);
        assertThat(registry.activeSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ingestAudioChunk validates, transitions session, and dispatches to publisher")
    void ingestAudioChunkDispatches() {
        CallSessionId sessionId = CallSessionId.generate();
        service.startSession(sessionId);

        AudioChunk chunk =
                new AudioChunk(
                        sessionId, 0L, Instant.now(), new byte[640], AudioFormat.PCM_16KHZ_MONO);
        service.ingestAudioChunk(chunk);

        CallSession session = registry.findById(sessionId).orElseThrow();
        assertThat(session.state()).isInstanceOf(CallSessionState.Streaming.class);
        assertThat(publisher.publishedChunks).hasSize(1);
        assertThat(publisher.publishedChunks.getFirst()).isEqualTo(chunk);
    }

    @Test
    @DisplayName("ingestAudioChunk throws CallSessionNotFoundException for unknown session")
    void ingestAudioChunkUnknownSessionThrows() {
        CallSessionId unknownId = CallSessionId.generate();
        AudioChunk chunk =
                new AudioChunk(
                        unknownId, 0L, Instant.now(), new byte[640], AudioFormat.PCM_16KHZ_MONO);

        assertThatThrownBy(() -> service.ingestAudioChunk(chunk))
                .isInstanceOf(CallSessionNotFoundException.class)
                .hasMessageContaining("Session not found: " + unknownId);

        assertThat(publisher.publishedChunks).isEmpty();
    }

    @Test
    @DisplayName("terminateSession transitions session to Disconnected and updates registry")
    void terminateSessionTransitionsAndUpdates() {
        CallSessionId sessionId = CallSessionId.generate();
        service.startSession(sessionId);

        service.terminateSession(sessionId, DisconnectReason.CLIENT_HANGUP);

        CallSession session = registry.findById(sessionId).orElseThrow();
        assertThat(session.state()).isInstanceOf(CallSessionState.Disconnected.class);
    }

    @Test
    @DisplayName("terminateSession throws CallSessionNotFoundException for unknown session")
    void terminateSessionUnknownThrows() {
        CallSessionId unknownId = CallSessionId.generate();

        assertThatThrownBy(
                        () -> service.terminateSession(unknownId, DisconnectReason.CLIENT_HANGUP))
                .isInstanceOf(CallSessionNotFoundException.class)
                .hasMessageContaining("Session not found: " + unknownId);
    }

    @Test
    @DisplayName("getSession returns session if present")
    void getSessionPresent() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession started = service.startSession(sessionId);

        Optional<CallSession> retrieved = service.getSession(sessionId);

        assertThat(retrieved).contains(started);
    }

    private static class InMemorySessionRegistry implements SessionRegistryPort {
        private final Map<CallSessionId, CallSession> map = new ConcurrentHashMap<>();

        @Override
        public void save(CallSession session) {
            map.put(session.id(), session);
        }

        @Override
        public Optional<CallSession> findById(CallSessionId sessionId) {
            return Optional.ofNullable(map.get(sessionId));
        }

        @Override
        public void remove(CallSessionId sessionId) {
            map.remove(sessionId);
        }

        @Override
        public boolean exists(CallSessionId sessionId) {
            return map.containsKey(sessionId);
        }

        @Override
        public int activeSessionCount() {
            return map.size();
        }
    }

    private static class RecordingAudioFramePublisher implements AudioFramePublisherPort {
        private final List<AudioChunk> publishedChunks = new ArrayList<>();

        @Override
        public void publishAudioFrame(CallId callId, long sequenceNumber, byte[] audioPayload) {
            // Implemented by default publishAudioChunk
        }

        @Override
        public void publishAudioChunk(AudioChunk chunk) {
            publishedChunks.add(chunk);
        }
    }
}
