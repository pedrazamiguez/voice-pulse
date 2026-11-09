package com.voicepulse.gateway.infrastructure.echo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
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
import java.util.Optional;
import java.util.function.Function;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoopbackAudioEchoAdapter Port Implementation Tests")
class LoopbackAudioEchoAdapterTest {

    @Mock private ConcurrentSessionRegistry sessionRegistry;

    @InjectMocks private LoopbackAudioEchoAdapter adapter;

    @Captor private ArgumentCaptor<WebSocketMessage> msgCaptor;

    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        bufferFactory = new DefaultDataBufferFactory();
    }

    private WebSocketSession createMockSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.binaryMessage(any()))
                .thenAnswer(
                        invocation -> {
                            Function<DataBufferFactory, DataBuffer> fn = invocation.getArgument(0);
                            return new WebSocketMessage(
                                    WebSocketMessage.Type.BINARY, fn.apply(bufferFactory));
                        });
        return session;
    }

    private AudioChunk createChunk(CallSessionId sessionId, long seq, byte[] payload) {
        return Instancio.of(AudioChunk.class)
                .set(field(AudioChunk::sessionId), sessionId)
                .set(field(AudioChunk::sequenceNumber), seq)
                .set(field(AudioChunk::pcmData), payload)
                .set(field(AudioChunk::format), AudioFormat.PCM_16KHZ_MONO)
                .create();
    }

    @Test
    @DisplayName("publishAudioChunk echoes binary frame to active WebSocketSession and updates RTT")
    void publishAudioChunk_echoesBinaryFrameAndUpdatesRtt() {
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketSession session = createMockSession();

        when(sessionRegistry.getSession(sessionId)).thenReturn(Optional.of(session));
        when(sessionRegistry.emitMessage(any(), any())).thenReturn(true);

        byte[] payload = new byte[] {0x10, 0x20, 0x30, 0x40};
        AudioChunk chunk = createChunk(sessionId, 0L, payload);

        adapter.publishAudioChunk(chunk);

        verify(sessionRegistry).emitMessage(any(), msgCaptor.capture());

        WebSocketMessage emittedMsg = msgCaptor.getValue();
        assertThat(emittedMsg.getType()).isEqualTo(WebSocketMessage.Type.BINARY);

        byte[] capturedBytes = new byte[emittedMsg.getPayload().readableByteCount()];
        emittedMsg.getPayload().read(capturedBytes);
        assertThat(capturedBytes).isEqualTo(payload);

        EchoLatencyMetrics metrics = adapter.getMetrics(sessionId);
        assertThat(metrics.totalFramesEchoed()).isEqualTo(1L);
        assertThat(metrics.lastRttMicros()).isGreaterThanOrEqualTo(0L);
        assertThat(metrics.lastRttMs()).isGreaterThanOrEqualTo(0.0);
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
        AudioChunk chunk0 = createChunk(sessionId, 0L, payload);
        AudioChunk chunk1 = createChunk(sessionId, 1L, payload);
        AudioChunk chunk3 = createChunk(sessionId, 3L, payload);

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

        AudioChunk chunk = createChunk(sessionId, 0L, new byte[] {1, 2});

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

        byte[] payload = new byte[] {9, 8};
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
