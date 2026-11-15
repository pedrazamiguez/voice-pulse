package com.voicepulse.gateway.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.gateway.infrastructure.config.GatewayBridgeProperties;
import com.voicepulse.gateway.infrastructure.registry.ConcurrentSessionRegistry;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallSessionId;
import io.netty.buffer.PooledByteBufAllocator;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketOperatorBridgeAdapter Unit Tests")
class WebSocketOperatorBridgeAdapterTest {

    private final NettyDataBufferFactory bufferFactory =
            new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);

    @Mock private ConcurrentSessionRegistry sessionRegistry;

    private CallBridgeRegistry callBridgeRegistry;
    private WebSocketOperatorBridgeAdapter adapter;

    @BeforeEach
    void setUp() {
        callBridgeRegistry = new CallBridgeRegistry(new GatewayBridgeProperties(16));
        adapter = new WebSocketOperatorBridgeAdapter(sessionRegistry, callBridgeRegistry);
    }

    @Test
    @DisplayName(
            "relayCallerAudio encodes binary message and pushes into connected operator endpoint")
    void relaysCallerAudioToConnectedOperator() {
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketSession operatorWs = mockBinarySession(true);
        adapter.activateBridge(sessionId, "op-1");
        OperatorAudioEndpoint endpoint =
                callBridgeRegistry
                        .registerOperatorEndpoint(sessionId, "op-1", operatorWs)
                        .orElseThrow();

        byte[] pcm = new byte[] {1, 2, 3, 4};
        AudioChunk chunk =
                new AudioChunk(sessionId, 0L, Instant.now(), pcm, AudioFormat.PCM_16KHZ_MONO);
        adapter.relayCallerAudio(chunk);

        WebSocketMessage emitted = endpoint.outboundFlux().take(1).blockFirst();
        assertThat(emitted).isNotNull();
        assertThat(emitted.getType()).isEqualTo(WebSocketMessage.Type.BINARY);
        byte[] actual = new byte[emitted.getPayload().readableByteCount()];
        emitted.getPayload().read(actual);
        assertThat(actual).isEqualTo(pcm);
        NettyBufferReleaser.release(emitted);
    }

    @Test
    @DisplayName(
            "relayOperatorAudio emits binary PCM payload to caller session via"
                    + " ConcurrentSessionRegistry")
    void relaysOperatorAudioToCaller() {
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketSession callerWs = mockBinarySession(true);
        when(sessionRegistry.getSession(sessionId)).thenReturn(Optional.of(callerWs));
        when(sessionRegistry.emitMessage(eq(sessionId), any(WebSocketMessage.class)))
                .thenReturn(true);

        byte[] pcm = new byte[] {9, 8, 7, 6};
        adapter.relayOperatorAudio(sessionId, "op-1", pcm);

        ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(sessionRegistry).emitMessage(eq(sessionId), captor.capture());
        WebSocketMessage msg = captor.getValue();
        NettyDataBuffer nettyBuf = (NettyDataBuffer) msg.getPayload();
        assertThat(nettyBuf.readableByteCount()).isEqualTo(4);
        NettyBufferReleaser.release(msg);
    }

    @Test
    @DisplayName(
            "drops frames safely without allocating buffers when sessions are closed or detached")
    void dropsSafelyWhenClosedOrDetached() {
        CallSessionId sessionId = CallSessionId.generate();
        adapter.activateBridge(sessionId, "op-3");
        // No connected operator yet -> no allocation, no error
        adapter.relayCallerAudio(
                new AudioChunk(
                        sessionId, 0L, Instant.now(), new byte[640], AudioFormat.PCM_16KHZ_MONO));

        WebSocketSession closedCaller = mock(WebSocketSession.class);
        when(closedCaller.isOpen()).thenReturn(false);
        when(sessionRegistry.getSession(sessionId)).thenReturn(Optional.of(closedCaller));
        adapter.relayOperatorAudio(sessionId, "op-3", new byte[640]);
        verify(closedCaller, never()).binaryMessage(any());
        verify(sessionRegistry, never()).emitMessage(any(), any());
        adapter.detachOperator(sessionId, "op-3");
        adapter.closeBridge(sessionId);
    }

    @SuppressWarnings("unchecked")
    private WebSocketSession mockBinarySession(boolean open) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(open);
        when(ws.binaryMessage(any()))
                .thenAnswer(
                        inv -> {
                            Function<DataBufferFactory, DataBuffer> fn = inv.getArgument(0);
                            return new WebSocketMessage(
                                    WebSocketMessage.Type.BINARY, fn.apply(bufferFactory));
                        });
        return ws;
    }
}
