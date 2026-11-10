package com.voicepulse.gateway.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.voicepulse.gateway.domain.exception.CallSessionNotFoundException;
import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.gateway.domain.model.CallSessionState;
import com.voicepulse.gateway.domain.model.DisconnectReason;
import com.voicepulse.gateway.domain.ports.outbound.AudioFramePublisherPort;
import com.voicepulse.gateway.domain.ports.outbound.AudioPlaybackControlPort;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import java.util.Optional;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CallSessionService Domain Service Tests")
class CallSessionServiceTest {

    @Mock private SessionRegistryPort registry;

    @Mock private AudioFramePublisherPort publisher;

    @Mock private AudioPlaybackControlPort audioPlaybackControlPort;

    @InjectMocks private CallSessionService service;

    @Captor private ArgumentCaptor<AudioChunk> chunkCaptor;

    @Captor private ArgumentCaptor<CallSession> sessionCaptor;

    @Test
    @DisplayName("startSession creates, registers, and returns active CallSession")
    void startSessionRegisters() {
        CallSessionId sessionId = CallSessionId.generate();

        CallSession session = service.startSession(sessionId);

        assertThat(session).isNotNull();
        assertThat(session.id()).isEqualTo(sessionId);
        verify(registry).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().id()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("ingestAudioChunk validates, transitions session, and dispatches to publisher")
    void ingestAudioChunkDispatches() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now());
        when(registry.findById(sessionId)).thenReturn(Optional.of(session));

        AudioChunk chunk =
                Instancio.of(AudioChunk.class)
                        .set(field(AudioChunk::sessionId), sessionId)
                        .set(field(AudioChunk::sequenceNumber), 0L)
                        .set(field(AudioChunk::pcmData), new byte[640])
                        .set(field(AudioChunk::format), AudioFormat.PCM_16KHZ_MONO)
                        .create();

        service.ingestAudioChunk(chunk);

        verify(publisher).publishAudioChunk(chunkCaptor.capture());
        assertThat(chunkCaptor.getValue()).isEqualTo(chunk);
        verify(registry).save(session);
        assertThat(session.state()).isInstanceOf(CallSessionState.Streaming.class);
        verifyNoMoreInteractions(publisher);
    }

    @Test
    @DisplayName("ingestAudioChunk throws CallSessionNotFoundException for unknown session")
    void ingestAudioChunkUnknownSessionThrows() {
        CallSessionId unknownId = CallSessionId.generate();
        when(registry.findById(unknownId)).thenReturn(Optional.empty());

        AudioChunk chunk =
                Instancio.of(AudioChunk.class)
                        .set(field(AudioChunk::sessionId), unknownId)
                        .set(field(AudioChunk::sequenceNumber), 0L)
                        .set(field(AudioChunk::pcmData), new byte[640])
                        .set(field(AudioChunk::format), AudioFormat.PCM_16KHZ_MONO)
                        .create();

        assertThatThrownBy(() -> service.ingestAudioChunk(chunk))
                .isInstanceOf(CallSessionNotFoundException.class)
                .hasMessageContaining("Session not found: " + unknownId);

        verify(publisher, never()).publishAudioChunk(any());
    }

    @Test
    @DisplayName("terminateSession transitions session to Disconnected and updates registry")
    void terminateSessionTransitionsAndUpdates() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now());
        when(registry.findById(sessionId)).thenReturn(Optional.of(session));

        service.terminateSession(sessionId, DisconnectReason.CLIENT_HANGUP);

        verify(registry).save(session);
        assertThat(session.state()).isInstanceOf(CallSessionState.Disconnected.class);
    }

    @Test
    @DisplayName("terminateSession throws CallSessionNotFoundException for unknown session")
    void terminateSessionUnknownThrows() {
        CallSessionId unknownId = CallSessionId.generate();
        when(registry.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> service.terminateSession(unknownId, DisconnectReason.CLIENT_HANGUP))
                .isInstanceOf(CallSessionNotFoundException.class)
                .hasMessageContaining("Session not found: " + unknownId);
    }

    @Test
    @DisplayName("getSession returns session if present")
    void getSessionPresent() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now());
        when(registry.findById(sessionId)).thenReturn(Optional.of(session));

        Optional<CallSession> retrieved = service.getSession(sessionId);

        assertThat(retrieved).contains(session);
    }

    @Test
    @DisplayName("interruptPlayback delegates to AudioPlaybackControlPort when session exists")
    void interruptPlaybackDelegatesWhenSessionExists() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now());
        when(registry.findById(sessionId)).thenReturn(Optional.of(session));

        service.interruptPlayback(sessionId);

        verify(audioPlaybackControlPort).interruptPlayback(sessionId);
    }

    @Test
    @DisplayName(
            "interruptPlayback throws CallSessionNotFoundException when session does not exist")
    void interruptPlaybackThrowsWhenSessionNotFound() {
        CallSessionId unknownId = CallSessionId.generate();
        when(registry.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.interruptPlayback(unknownId))
                .isInstanceOf(CallSessionNotFoundException.class)
                .hasMessageContaining("Session not found: " + unknownId);

        verify(audioPlaybackControlPort, never()).interruptPlayback(any());
    }

    @Test
    @DisplayName("interruptPlayback throws NullPointerException when sessionId is null")
    void interruptPlaybackThrowsOnNullSessionId() {
        assertThatThrownBy(() -> service.interruptPlayback(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId cannot be null");
    }
}
