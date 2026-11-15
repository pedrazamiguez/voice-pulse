package com.voicepulse.gateway.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.gateway.domain.model.BridgeAuthorizationStatus;
import com.voicepulse.gateway.domain.ports.inbound.BridgeOperatorAudioUseCase;
import com.voicepulse.gateway.infrastructure.config.GatewayBridgeProperties;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallSessionId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
@DisplayName("OperatorAudioWebSocketHandler Unit Tests")
class OperatorAudioWebSocketHandlerTest {

    private final NettyDataBufferFactory bufferFactory =
            new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);

    @Mock private BridgeOperatorAudioUseCase bridgeOperatorAudioUseCase;

    private CallBridgeRegistry callBridgeRegistry;
    private OperatorAudioUriParser uriParser;
    private OperatorAudioWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        callBridgeRegistry = new CallBridgeRegistry(new GatewayBridgeProperties(64));
        uriParser = new OperatorAudioUriParser();
        handler =
                new OperatorAudioWebSocketHandler(
                        bridgeOperatorAudioUseCase, callBridgeRegistry, uriParser);
    }

    @Test
    @DisplayName("rejects invalid URI with CloseStatus 1008 POLICY_VIOLATION")
    void rejectsInvalidUri() {
        WebSocketSession session = mockSession("ws://localhost:8080/ws/invalid/path");
        when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

        handler.handle(session).block();

        ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    @DisplayName("rejects PendingReroute with CloseStatus 1013 TRY_AGAIN_LATER")
    void rejectsPendingRerouteWith1013() {
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketSession session =
                mockSession("ws://localhost:8080/ws/operators/op-1/calls/" + sessionId + "/audio");
        when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());
        when(bridgeOperatorAudioUseCase.authorizeOperatorBridge(sessionId, "op-1"))
                .thenReturn(new BridgeAuthorizationStatus.PendingReroute(sessionId));

        handler.handle(session).block();

        ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo(1013);
    }

    @Test
    @DisplayName("rejects UnauthorizedOperator and SessionUnavailable with CloseStatus 1008")
    void rejectsUnauthorizedAndUnavailableWith1008() {
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketSession sessionUnauthorized =
                mockSession("ws://localhost:8080/ws/operators/op-2/calls/" + sessionId + "/audio");
        when(sessionUnauthorized.close(any(CloseStatus.class))).thenReturn(Mono.empty());
        when(bridgeOperatorAudioUseCase.authorizeOperatorBridge(sessionId, "op-2"))
                .thenReturn(
                        new BridgeAuthorizationStatus.UnauthorizedOperator(
                                sessionId, "op-2", "op-1"));

        handler.handle(sessionUnauthorized).block();

        ArgumentCaptor<CloseStatus> captor1 = ArgumentCaptor.forClass(CloseStatus.class);
        verify(sessionUnauthorized).close(captor1.capture());
        assertThat(captor1.getValue().getCode()).isEqualTo(1008);

        WebSocketSession sessionUnavailable =
                mockSession("ws://localhost:8080/ws/operators/op-1/calls/" + sessionId + "/audio");
        when(sessionUnavailable.close(any(CloseStatus.class))).thenReturn(Mono.empty());
        when(bridgeOperatorAudioUseCase.authorizeOperatorBridge(sessionId, "op-1"))
                .thenReturn(new BridgeAuthorizationStatus.SessionUnavailable(sessionId));

        handler.handle(sessionUnavailable).block();

        ArgumentCaptor<CloseStatus> captor2 = ArgumentCaptor.forClass(CloseStatus.class);
        verify(sessionUnavailable).close(captor2.capture());
        assertThat(captor2.getValue().getCode()).isEqualTo(1008);
    }

    @Test
    @DisplayName("relays valid binary PCM frames, ignores text frames, and frees Netty buffers")
    void relaysValidBinaryFramesAndDetachesOnClose() {
        CallSessionId sessionId = CallSessionId.generate();
        String operatorId = "op-88";
        callBridgeRegistry.activateBridge(sessionId, operatorId);
        when(bridgeOperatorAudioUseCase.authorizeOperatorBridge(sessionId, operatorId))
                .thenReturn(new BridgeAuthorizationStatus.Authorized(sessionId, operatorId));

        WebSocketSession session =
                mockSession(
                        "ws://localhost:8080/ws/operators/"
                                + operatorId
                                + "/calls/"
                                + sessionId
                                + "/audio");
        when(session.send(any()))
                .thenAnswer(
                        inv -> {
                            Flux<WebSocketMessage> flux = inv.getArgument(0);
                            return flux.then();
                        });

        NettyDataBuffer textBuf = allocateNettyBuffer("hello".getBytes(StandardCharsets.UTF_8));
        WebSocketMessage textMsg = new WebSocketMessage(WebSocketMessage.Type.TEXT, textBuf);

        byte[] pcm = new byte[640];
        pcm[0] = 42;
        NettyDataBuffer pcmBuf = allocateNettyBuffer(pcm);
        WebSocketMessage binaryMsg = new WebSocketMessage(WebSocketMessage.Type.BINARY, pcmBuf);

        when(session.receive()).thenReturn(Flux.just(textMsg, binaryMsg));

        handler.handle(session).block();

        verify(bridgeOperatorAudioUseCase).relayOperatorAudio(sessionId, operatorId, pcm);
        verify(bridgeOperatorAudioUseCase).handleOperatorDisconnected(sessionId, operatorId);
        // Simulate Reactor Netty receive releasing original ref for binary frame (retain+release
        // balances inside handler)
        assertThat(textBuf.getNativeBuffer().refCnt()).isZero();
        pcmBuf.getNativeBuffer().release();
        assertThat(pcmBuf.getNativeBuffer().refCnt()).isZero();
    }

    @Test
    @DisplayName(
            "DoS guard rejects oversized frames above 64 KB with CloseStatus 1009 and releases"
                    + " buffer")
    void rejectsOversizedFramesWith1009() {
        CallSessionId sessionId = CallSessionId.generate();
        String operatorId = "op-9";
        callBridgeRegistry.activateBridge(sessionId, operatorId);
        when(bridgeOperatorAudioUseCase.authorizeOperatorBridge(sessionId, operatorId))
                .thenReturn(new BridgeAuthorizationStatus.Authorized(sessionId, operatorId));

        WebSocketSession session =
                mockSession(
                        "ws://localhost:8080/ws/operators/"
                                + operatorId
                                + "/calls/"
                                + sessionId
                                + "/audio");
        when(session.send(any()))
                .thenAnswer(
                        inv -> {
                            Flux<WebSocketMessage> flux = inv.getArgument(0);
                            return flux.then();
                        });
        when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

        byte[] oversized = new byte[AudioChunk.MAX_CHUNK_SIZE_BYTES + 2];
        NettyDataBuffer bigBuf = allocateNettyBuffer(oversized);
        when(session.receive())
                .thenReturn(Flux.just(new WebSocketMessage(WebSocketMessage.Type.BINARY, bigBuf)));

        handler.handle(session).block();

        ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo(1009);
        verify(bridgeOperatorAudioUseCase, never()).relayOperatorAudio(any(), any(), any());
        verify(bridgeOperatorAudioUseCase).handleOperatorDisconnected(sessionId, operatorId);
        bigBuf.getNativeBuffer().release();
        assertThat(bigBuf.getNativeBuffer().refCnt()).isZero();
    }

    private WebSocketSession mockSession(String uri) {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo info =
                new HandshakeInfo(URI.create(uri), new HttpHeaders(), Mono.empty(), null);
        when(session.getHandshakeInfo()).thenReturn(info);
        return session;
    }

    private NettyDataBuffer allocateNettyBuffer(byte[] payload) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(payload.length);
        buf.writeBytes(payload);
        return bufferFactory.wrap(buf);
    }
}
