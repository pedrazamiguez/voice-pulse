package com.voicepulse.gateway.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voicepulse.gateway.infrastructure.config.GatewayBridgeProperties;
import com.voicepulse.shared.model.CallSessionId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

@DisplayName("CallBridgeRegistry Unit & Netty Buffer Cleanup Tests")
class CallBridgeRegistryTest {

    private final NettyDataBufferFactory bufferFactory =
            new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);

    @Test
    @DisplayName(
            "registers assigned operator endpoint, relays queued frames, and releases on"
                    + " detach/close")
    void registersRelaysAndReleasesBuffersOnClose() {
        CallBridgeRegistry registry = new CallBridgeRegistry(new GatewayBridgeProperties(2));
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketSession wsSession = mock(WebSocketSession.class);
        when(wsSession.isOpen()).thenReturn(true);

        assertThat(registry.registerOperatorEndpoint(sessionId, "op-1", wsSession)).isEmpty();

        registry.activateBridge(sessionId, "op-1");
        assertThat(registry.registerOperatorEndpoint(sessionId, "wrong-op", wsSession)).isEmpty();

        Optional<OperatorAudioEndpoint> registered =
                registry.registerOperatorEndpoint(sessionId, "op-1", wsSession);
        assertThat(registered).isPresent();
        assertThat(registry.getAssignedOperatorId(sessionId)).contains("op-1");
        assertThat(registry.getConnectedSession(sessionId)).contains(wsSession);

        NettyDataBuffer buf1 = allocateFrame(640);
        NettyDataBuffer buf2 = allocateFrame(640);
        NettyDataBuffer overflowBuf = allocateFrame(640);

        assertThat(
                        registry.emitMessage(
                                sessionId,
                                new WebSocketMessage(WebSocketMessage.Type.BINARY, buf1)))
                .isTrue();
        assertThat(
                        registry.emitMessage(
                                sessionId,
                                new WebSocketMessage(WebSocketMessage.Type.BINARY, buf2)))
                .isTrue();
        // Queue capacity = 2; 3rd emit hits FAIL_OVERFLOW and must release immediately
        assertThat(
                        registry.emitMessage(
                                sessionId,
                                new WebSocketMessage(WebSocketMessage.Type.BINARY, overflowBuf)))
                .isFalse();
        assertThat(overflowBuf.getNativeBuffer().refCnt()).isZero();

        // Detaching frees queued buffers while keeping active assignment for reconnection
        registry.detachOperator(sessionId, "op-1");
        assertThat(buf1.getNativeBuffer().refCnt()).isZero();
        assertThat(buf2.getNativeBuffer().refCnt()).isZero();
        assertThat(registry.getConnectedSession(sessionId)).isEmpty();
        assertThat(registry.getAssignedOperatorId(sessionId)).contains("op-1");

        // Reconnect and verify closeBridge tears down assignment + drains queued buffer
        Optional<OperatorAudioEndpoint> reconnected =
                registry.registerOperatorEndpoint(sessionId, "op-1", wsSession);
        assertThat(reconnected).isPresent();
        NettyDataBuffer buf3 = allocateFrame(640);
        assertThat(
                        registry.emitMessage(
                                sessionId,
                                new WebSocketMessage(WebSocketMessage.Type.BINARY, buf3)))
                .isTrue();

        registry.closeBridge(sessionId);
        assertThat(buf3.getNativeBuffer().refCnt()).isZero();
        assertThat(registry.getAssignedOperatorId(sessionId)).isEmpty();
        assertThat(registry.getConnectedSession(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("emitMessage releases payload when operator WebSocket is absent or closed")
    void releasesBufferWhenEndpointMissingOrClosed() {
        CallBridgeRegistry registry = new CallBridgeRegistry(new GatewayBridgeProperties(16));
        CallSessionId sessionId = CallSessionId.generate();
        NettyDataBuffer buf = allocateFrame(320);

        assertThat(
                        registry.emitMessage(
                                sessionId, new WebSocketMessage(WebSocketMessage.Type.BINARY, buf)))
                .isFalse();
        assertThat(buf.getNativeBuffer().refCnt()).isZero();

        WebSocketSession closedSession = mock(WebSocketSession.class);
        when(closedSession.isOpen()).thenReturn(false);
        registry.activateBridge(sessionId, "op-2");
        registry.registerOperatorEndpoint(sessionId, "op-2", closedSession);
        NettyDataBuffer bufClosed = allocateFrame(320);
        assertThat(
                        registry.emitMessage(
                                sessionId,
                                new WebSocketMessage(WebSocketMessage.Type.BINARY, bufClosed)))
                .isFalse();
        assertThat(bufClosed.getNativeBuffer().refCnt()).isZero();
    }

    private NettyDataBuffer allocateFrame(int sizeBytes) {
        ByteBuf nativeBuf = PooledByteBufAllocator.DEFAULT.directBuffer(sizeBytes);
        nativeBuf.writeBytes(new byte[sizeBytes]);
        return bufferFactory.wrap(nativeBuf);
    }
}
