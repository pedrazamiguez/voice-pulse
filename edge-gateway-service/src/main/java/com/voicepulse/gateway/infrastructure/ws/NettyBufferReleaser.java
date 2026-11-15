package com.voicepulse.gateway.infrastructure.ws;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.web.reactive.socket.WebSocketMessage;

/**
 * Deterministic Netty buffer release helper shared by WebSocket handlers and outbound bridge queues
 * to prevent off-heap memory leaks under {@code PARANOID} leak detection.
 */
public final class NettyBufferReleaser {

    private NettyBufferReleaser() {}

    /**
     * Releases the underlying buffer of a {@link WebSocketMessage} if still referenced.
     *
     * @param message WebSocket message whose payload buffer should be released
     */
    public static void release(WebSocketMessage message) {
        if (message != null) {
            release(message.getPayload());
        }
    }

    /**
     * Releases a Spring {@link DataBuffer} (or native Netty {@link ByteBuf}) if still referenced.
     *
     * @param dataBuffer buffer to release
     */
    public static void release(DataBuffer dataBuffer) {
        if (dataBuffer == null) {
            return;
        }
        if (dataBuffer instanceof NettyDataBuffer nettyDataBuffer) {
            ByteBuf nativeBuffer = nettyDataBuffer.getNativeBuffer();
            if (nativeBuffer.refCnt() > 0) {
                ReferenceCountUtil.release(nativeBuffer);
            }
        } else {
            DataBufferUtils.release(dataBuffer);
        }
    }
}
