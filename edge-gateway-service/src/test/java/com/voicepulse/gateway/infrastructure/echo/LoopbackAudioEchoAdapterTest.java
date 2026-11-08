package com.voicepulse.gateway.infrastructure.echo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.gateway.infrastructure.registry.ConcurrentSessionRegistry;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

class LoopbackAudioEchoAdapterTest {

    private ConcurrentSessionRegistry sessionRegistry;
    private LoopbackAudioEchoAdapter adapter;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        sessionRegistry = mock(ConcurrentSessionRegistry.class);
        adapter = new LoopbackAudioEchoAdapter(sessionRegistry);
        bufferFactory = new DefaultDataBufferFactory();
    }

    private WebSocketSession createMockSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.binaryMessage(any()))
                .thenAnswer(
                        invocation -> {
                            java.util.function.Function<
                                            org.springframework.core.io.buffer.DataBufferFactory,
                                            org.springframework.core.io.buffer.DataBuffer>
                                    fn = invocation.getArgument(0);
                            return new WebSocketMessage(
                                    WebSocketMessage.Type.BINARY, fn.apply(bufferFactory));
                        });
        return session;
    }

    @Test
    @DisplayName("publishAudioChunk echoes binary frame to active WebSocketSession and updates RTT")
    void publishAudioChunk_echoesBinaryFrameAndUpdatesRtt() {
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketSession session = createMockSession();

        when(sessionRegistry.getSession(sessionId)).thenReturn(Optional.of(session));
        when(sessionRegistry.emitMessage(any(), any())).thenReturn(true);

        byte[] payload = new byte[] {0x10, 0x20, 0x30, 0x40};
        Instant chunkTimestamp = Instant.now().minusMillis(5);
        AudioChunk chunk =
                new AudioChunk(sessionId, 0L, chunkTimestamp, payload, AudioFormat.PCM_16KHZ_MONO);

        adapter.publishAudioChunk(chunk);

        ArgumentCaptor<WebSocketMessage> msgCaptor =
                ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(sessionRegistry).emitMessage(any(), msgCaptor.capture());

        WebSocketMessage emittedMsg = msgCaptor.getValue();
        assertThat(emittedMsg.getType()).isEqualTo(WebSocketMessage.Type.BINARY);

        byte[] capturedBytes = new byte[emittedMsg.getPayload().readableByteCount()];
        emittedMsg.getPayload().read(capturedBytes);
        assertThat(capturedBytes).isEqualTo(payload);

        EchoLatencyMetrics metrics = adapter.getMetrics(sessionId);
        assertThat(metrics.totalFramesEchoed()).isEqualTo(1L);
        assertThat(metrics.lastRttMicros()).isGreaterThan(0L);
        assertThat(metrics.lastRttMs()).isGreaterThan(0.0);
        assertThat(metrics.sequenceDiscontinuities()).isZero();
    }

    @Test
    @DisplayName("publishAudioChunk detects sequence gaps and records discontinuities")
    void publishAudioChunk_detectsSequenceGaps() {
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketSession session = createMockSession();

        when(sessionRegistry.getSession(sessionId)).thenReturn(Optional.of(session));
        when(sessionRegistry.emitMessage(any(), any())).thenReturn(true);

        byte[] payload = new byte[] {1, 2, 3, 4};
        AudioChunk chunk0 =
                new AudioChunk(sessionId, 0L, Instant.now(), payload, AudioFormat.PCM_16KHZ_MONO);
        AudioChunk chunk1 =
                new AudioChunk(sessionId, 1L, Instant.now(), payload, AudioFormat.PCM_16KHZ_MONO);
        AudioChunk chunk3 =
                new AudioChunk(sessionId, 3L, Instant.now(), payload, AudioFormat.PCM_16KHZ_MONO);

        adapter.publishAudioChunk(chunk0);
        adapter.publishAudioChunk(chunk1);
        adapter.publishAudioChunk(chunk3); // Gap: expected 2L, got 3L

        EchoLatencyMetrics metrics = adapter.getMetrics(sessionId);
        assertThat(metrics.totalFramesEchoed()).isEqualTo(3L);
        assertThat(metrics.sequenceDiscontinuities()).isEqualTo(1L);
    }

    @Test
    @DisplayName("publishAudioChunk drops gracefully when session is absent or closed")
    void publishAudioChunk_dropsGracefullyWhenSessionAbsentOrClosed() {
        CallSessionId sessionId = CallSessionId.generate();
        when(sessionRegistry.getSession(sessionId)).thenReturn(Optional.empty());

        AudioChunk chunk =
                new AudioChunk(
                        sessionId,
                        0L,
                        Instant.now(),
                        new byte[] {1, 2},
                        AudioFormat.PCM_16KHZ_MONO);

        adapter.publishAudioChunk(chunk);

        verify(sessionRegistry, never()).emitMessage(any(), any());
        EchoLatencyMetrics metrics = adapter.getMetrics(sessionId);
        assertThat(metrics.totalFramesEchoed()).isZero();

        // Closed session case
        WebSocketSession closedSession = mock(WebSocketSession.class);
        when(closedSession.isOpen()).thenReturn(false);
        when(sessionRegistry.getSession(sessionId)).thenReturn(Optional.of(closedSession));

        adapter.publishAudioChunk(chunk);
        verify(sessionRegistry, never()).emitMessage(any(), any());
    }

    @Test
    @DisplayName("publishAudioFrame SPI fallback delegates to echo logic correctly")
    void publishAudioFrame_delegatesCorrectly() {
        CallSessionId sessionId = CallSessionId.generate();
        CallId callId = sessionId.toCallId();
        WebSocketSession session = createMockSession();

        when(sessionRegistry.getSession(sessionId)).thenReturn(Optional.of(session));
        when(sessionRegistry.emitMessage(any(), any())).thenReturn(true);

        byte[] payload = new byte[] {9, 8, 7};
        adapter.publishAudioFrame(callId, 0L, payload);

        verify(sessionRegistry).emitMessage(any(), any());
        EchoLatencyMetrics metrics = adapter.getMetrics(sessionId);
        assertThat(metrics.totalFramesEchoed()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Null arguments throw NullPointerException")
    void nullArguments_throwException() {
        CallSessionId sessionId = CallSessionId.generate();
        CallId callId = sessionId.toCallId();

        assertThatThrownBy(() -> new LoopbackAudioEchoAdapter(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.publishAudioChunk(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.publishAudioFrame(null, 0L, new byte[0]))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.publishAudioFrame(callId, 0L, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter.getMetrics(null)).isInstanceOf(NullPointerException.class);
    }
}
