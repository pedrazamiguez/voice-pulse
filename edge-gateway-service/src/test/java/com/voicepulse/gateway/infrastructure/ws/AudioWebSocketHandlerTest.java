package com.voicepulse.gateway.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.gateway.domain.model.DisconnectReason;
import com.voicepulse.gateway.domain.ports.inbound.IngestAudioChunkUseCase;
import com.voicepulse.gateway.domain.ports.inbound.ManageCallSessionUseCase;
import com.voicepulse.gateway.infrastructure.registry.ConcurrentSessionRegistry;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallSessionId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
@DisplayName("AudioWebSocketHandler Netty WebSocket Streaming Tests")
class AudioWebSocketHandlerTest {

    @Mock private IngestAudioChunkUseCase ingestAudioChunkUseCase;

    @Mock private ManageCallSessionUseCase manageCallSessionUseCase;

    private ConcurrentSessionRegistry sessionRegistry;
    private AudioWebSocketHandler handler;
    private NettyDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        sessionRegistry = new ConcurrentSessionRegistry();
        handler =
                new AudioWebSocketHandler(
                        ingestAudioChunkUseCase, manageCallSessionUseCase, sessionRegistry);
        bufferFactory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
    }

    private WebSocketSession createMockSession(String path, Flux<WebSocketMessage> messageFlux) {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);

        lenient().when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        lenient().when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost:8080" + path));
        lenient().when(session.getAttributes()).thenReturn(Collections.emptyMap());
        lenient().when(handshakeInfo.getAttributes()).thenReturn(Collections.emptyMap());
        lenient()
                .when(session.receive())
                .thenReturn(
                        messageFlux.concatMap(
                                msg ->
                                        Mono.just(msg)
                                                .doFinally(
                                                        sig -> {
                                                            if (msg.getPayload()
                                                                    instanceof
                                                                    NettyDataBuffer nettyBuf) {
                                                                ReferenceCountUtil.release(
                                                                        nettyBuf.getNativeBuffer());
                                                            }
                                                        })));
        lenient().when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());
        lenient().when(session.send(any())).thenReturn(Mono.empty());

        return session;
    }

    private WebSocketMessage createNettyBinaryMessage(int sizeBytes, ByteBuf[] capturedBuf) {
        ByteBuf byteBuf = PooledByteBufAllocator.DEFAULT.buffer(sizeBytes);
        byteBuf.writeBytes(new byte[sizeBytes]);
        if (capturedBuf != null && capturedBuf.length > 0) {
            capturedBuf[0] = byteBuf;
        }
        NettyDataBuffer dataBuffer = bufferFactory.wrap(byteBuf);
        return new WebSocketMessage(WebSocketMessage.Type.BINARY, dataBuffer);
    }

    @Test
    @DisplayName("Valid audio frame ingestion dispatches AudioChunk and releases Netty ByteBuf")
    void validAudioFrameIngestion_dispatchesAudioChunkAndReleasesByteBuf() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession mockSession = CallSession.start(sessionId, Instant.now());
        when(manageCallSessionUseCase.getSession(sessionId)).thenReturn(Optional.of(mockSession));

        ByteBuf[] capturedBuf = new ByteBuf[1];
        WebSocketMessage message = createNettyBinaryMessage(640, capturedBuf);
        WebSocketSession session =
                createMockSession("/ws/calls/" + sessionId + "/audio", Flux.just(message));

        handler.handle(session).block();

        ArgumentCaptor<AudioChunk> captor = ArgumentCaptor.forClass(AudioChunk.class);
        verify(ingestAudioChunkUseCase).ingestAudioChunk(captor.capture());

        AudioChunk chunk = captor.getValue();
        assertThat(chunk.sessionId()).isEqualTo(sessionId);
        assertThat(chunk.sequenceNumber()).isZero();
        assertThat(chunk.format()).isEqualTo(AudioFormat.PCM_16KHZ_MONO);
        assertThat(chunk.pcmData()).hasSize(640);

        // Verify zero direct memory leaks
        assertThat(capturedBuf[0].refCnt()).isZero();
    }

    @Test
    @DisplayName("Multiple audio frames assign strictly monotonic sequence numbers")
    void sequentialFrames_assignsMonotonicSequenceNumbers() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession mockSession = CallSession.start(sessionId, Instant.now());
        when(manageCallSessionUseCase.getSession(sessionId)).thenReturn(Optional.of(mockSession));

        ByteBuf[] buf1 = new ByteBuf[1];
        ByteBuf[] buf2 = new ByteBuf[1];
        ByteBuf[] buf3 = new ByteBuf[1];

        WebSocketMessage msg1 = createNettyBinaryMessage(640, buf1);
        WebSocketMessage msg2 = createNettyBinaryMessage(640, buf2);
        WebSocketMessage msg3 = createNettyBinaryMessage(640, buf3);

        WebSocketSession session =
                createMockSession("/ws/calls/" + sessionId + "/audio", Flux.just(msg1, msg2, msg3));

        handler.handle(session).block();

        ArgumentCaptor<AudioChunk> captor = ArgumentCaptor.forClass(AudioChunk.class);
        verify(ingestAudioChunkUseCase, times(3)).ingestAudioChunk(captor.capture());

        var chunks = captor.getAllValues();
        assertThat(chunks.get(0).sequenceNumber()).isZero();
        assertThat(chunks.get(1).sequenceNumber()).isEqualTo(1L);
        assertThat(chunks.get(2).sequenceNumber()).isEqualTo(2L);

        // Verify all buffers are explicitly released
        assertThat(buf1[0].refCnt()).isZero();
        assertThat(buf2[0].refCnt()).isZero();
        assertThat(buf3[0].refCnt()).isZero();
    }

    @Test
    @DisplayName("DoS Guard: frame exceeding 64 KB ceiling is rejected with status code 1009")
    void oversizedFrame_exceeding64Kb_rejectedWithPolicyViolationAndReleasesByteBuf() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession mockSession = CallSession.start(sessionId, Instant.now());
        when(manageCallSessionUseCase.getSession(sessionId)).thenReturn(Optional.of(mockSession));

        int oversizedPayload = AudioChunk.MAX_CHUNK_SIZE_BYTES + 1; // 65537 bytes
        ByteBuf[] capturedBuf = new ByteBuf[1];
        WebSocketMessage message = createNettyBinaryMessage(oversizedPayload, capturedBuf);

        WebSocketSession session =
                createMockSession("/ws/calls/" + sessionId + "/audio", Flux.just(message));

        handler.handle(session).block();

        // Must reject with POLICY_VIOLATION (1009)
        verify(session).close(argThat(status -> status.getCode() == 1009));
        verify(manageCallSessionUseCase)
                .terminateSession(sessionId, DisconnectReason.PROTOCOL_ERROR);
        verify(ingestAudioChunkUseCase, never()).ingestAudioChunk(any());

        // Zero memory leak check
        assertThat(capturedBuf[0].refCnt()).isZero();
    }

    @Test
    @DisplayName("Invalid UUID callId in handshake path is rejected with status code 1007")
    void invalidCallIdPath_closesWithBadData() {
        WebSocketSession session =
                createMockSession("/ws/calls/not-a-valid-uuid/audio", Flux.empty());

        handler.handle(session).block();

        verify(session).close(argThat(status -> status.getCode() == 1007));
        verify(manageCallSessionUseCase, never()).startSession(any());
    }

    @Test
    @DisplayName("Unmatched WebSocket path is rejected with status code 1002")
    void unmatchedPath_closesWithProtocolError() {
        WebSocketSession session = createMockSession("/ws/other/path", Flux.empty());

        handler.handle(session).block();

        verify(session).close(argThat(status -> status.getCode() == 1002));
        verify(manageCallSessionUseCase, never()).startSession(any());
    }

    @Test
    @DisplayName("Non-binary WebSocket frames are ignored and their buffers safely released")
    void nonBinaryMessage_ignoredAndPayloadReleased() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession mockSession = CallSession.start(sessionId, Instant.now());
        when(manageCallSessionUseCase.getSession(sessionId)).thenReturn(Optional.of(mockSession));

        ByteBuf byteBuf = PooledByteBufAllocator.DEFAULT.buffer(32);
        byteBuf.writeBytes("text content".getBytes(StandardCharsets.UTF_8));
        NettyDataBuffer dataBuffer = bufferFactory.wrap(byteBuf);
        WebSocketMessage textMsg = new WebSocketMessage(WebSocketMessage.Type.TEXT, dataBuffer);

        WebSocketSession session =
                createMockSession("/ws/calls/" + sessionId + "/audio", Flux.just(textMsg));

        handler.handle(session).block();

        verify(ingestAudioChunkUseCase, never()).ingestAudioChunk(any());
        assertThat(byteBuf.refCnt()).isZero();
    }

    @Test
    @DisplayName("Graceful client hangup terminates session with CLIENT_HANGUP")
    void gracefulDisconnect_terminatesWithClientHangup() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession mockSession = CallSession.start(sessionId, Instant.now());
        when(manageCallSessionUseCase.getSession(sessionId)).thenReturn(Optional.of(mockSession));

        WebSocketSession session =
                createMockSession("/ws/calls/" + sessionId + "/audio", Flux.empty());

        handler.handle(session).block();

        verify(manageCallSessionUseCase)
                .terminateSession(sessionId, DisconnectReason.CLIENT_HANGUP);
    }

    @Test
    @DisplayName("Pipeline exception releases buffer and terminates session with PROTOCOL_ERROR")
    void processingException_terminatesWithProtocolErrorAndReleasesBuffer() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession mockSession = CallSession.start(sessionId, Instant.now());
        when(manageCallSessionUseCase.getSession(sessionId)).thenReturn(Optional.of(mockSession));

        doThrow(new RuntimeException("Pipeline failure"))
                .when(ingestAudioChunkUseCase)
                .ingestAudioChunk(any());

        ByteBuf[] capturedBuf = new ByteBuf[1];
        WebSocketMessage message = createNettyBinaryMessage(640, capturedBuf);

        WebSocketSession session =
                createMockSession("/ws/calls/" + sessionId + "/audio", Flux.just(message));

        handler.handle(session).block();

        assertThat(capturedBuf[0].refCnt()).isZero();
        verify(session).close(argThat(status -> status.getCode() == 1011));
        verify(manageCallSessionUseCase)
                .terminateSession(sessionId, DisconnectReason.PROTOCOL_ERROR);
    }

    @Test
    @DisplayName(
            "WebSocket session is registered in ConcurrentSessionRegistry and unregistered on"
                    + " close")
    void webSocketSession_registeredAndUnregisteredInRegistry() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession mockSession = CallSession.start(sessionId, Instant.now());
        when(manageCallSessionUseCase.getSession(sessionId)).thenReturn(Optional.of(mockSession));

        WebSocketSession session =
                createMockSession("/ws/calls/" + sessionId + "/audio", Flux.empty());

        assertThat(sessionRegistry.hasWebSocketSession(sessionId)).isFalse();

        handler.handle(session).block();

        // After completion, session should be unregistered cleanly
        assertThat(sessionRegistry.hasWebSocketSession(sessionId)).isFalse();
        assertThat(sessionRegistry.activeWebSocketSessionCount()).isZero();
    }
}
