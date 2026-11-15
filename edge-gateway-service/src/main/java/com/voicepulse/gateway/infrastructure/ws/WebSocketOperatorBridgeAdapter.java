package com.voicepulse.gateway.infrastructure.ws;

import com.voicepulse.gateway.domain.ports.outbound.OperatorAudioBridgePort;
import com.voicepulse.gateway.infrastructure.registry.ConcurrentSessionRegistry;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallSessionId;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * Secondary WebSocket adapter implementing {@link OperatorAudioBridgePort} by relaying 16 kHz
 * Linear PCM binary frames directly between {@link ConcurrentSessionRegistry} (caller) and {@link
 * CallBridgeRegistry} (human operator).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketOperatorBridgeAdapter implements OperatorAudioBridgePort {

    private final @NonNull ConcurrentSessionRegistry sessionRegistry;
    private final @NonNull CallBridgeRegistry callBridgeRegistry;

    @Override
    public void activateBridge(CallSessionId sessionId, String operatorId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        validateOperatorId(operatorId);
        callBridgeRegistry.activateBridge(sessionId, operatorId);
        log.info(
                "Activated WebSocket audio bridge: callId={}, operatorId={}",
                sessionId,
                operatorId);
    }

    @Override
    public void relayCallerAudio(AudioChunk chunk) {
        Objects.requireNonNull(chunk, "chunk cannot be null");
        callBridgeRegistry
                .getConnectedSession(chunk.sessionId())
                .ifPresentOrElse(
                        operatorSession -> {
                            if (!operatorSession.isOpen()) {
                                log.debug(
                                        "Dropped caller audio frame: operator WebSocket not open,"
                                                + " callId={}",
                                        chunk.sessionId());
                                return;
                            }
                            WebSocketMessage message =
                                    toBinaryMessage(operatorSession, chunk.pcmData());
                            boolean delivered =
                                    callBridgeRegistry.emitMessage(chunk.sessionId(), message);
                            log.debug(
                                    "Relayed caller audio frame to operator: callId={}, seq={},"
                                            + " bytes={}, delivered={}",
                                    chunk.sessionId(),
                                    chunk.sequenceNumber(),
                                    chunk.pcmData().length,
                                    delivered);
                        },
                        () ->
                                log.debug(
                                        "Dropped caller audio frame while awaiting operator"
                                                + " WebSocket attachment: callId={}, seq={}",
                                        chunk.sessionId(),
                                        chunk.sequenceNumber()));
    }

    @Override
    public void relayOperatorAudio(CallSessionId sessionId, String operatorId, byte[] pcmData) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        validateOperatorId(operatorId);
        Objects.requireNonNull(pcmData, "pcmData cannot be null");

        sessionRegistry
                .getSession(sessionId)
                .ifPresentOrElse(
                        callerSession -> {
                            if (!callerSession.isOpen()) {
                                log.debug(
                                        "Dropped operator audio frame: caller WebSocket not open,"
                                                + " callId={}, operatorId={}",
                                        sessionId,
                                        operatorId);
                                return;
                            }
                            WebSocketMessage message = toBinaryMessage(callerSession, pcmData);
                            boolean delivered = sessionRegistry.emitMessage(sessionId, message);
                            log.debug(
                                    "Relayed operator audio frame to caller: callId={},"
                                            + " operatorId={}, bytes={}, delivered={}",
                                    sessionId,
                                    operatorId,
                                    pcmData.length,
                                    delivered);
                        },
                        () ->
                                log.debug(
                                        "Dropped operator audio frame: caller WebSocket missing,"
                                                + " callId={}, operatorId={}",
                                        sessionId,
                                        operatorId));
    }

    @Override
    public void detachOperator(CallSessionId sessionId, String operatorId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        validateOperatorId(operatorId);
        callBridgeRegistry.detachOperator(sessionId, operatorId);
    }

    @Override
    public void closeBridge(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        callBridgeRegistry.closeBridge(sessionId);
    }

    private static WebSocketMessage toBinaryMessage(WebSocketSession session, byte[] pcmData) {
        return session.binaryMessage(
                factory -> {
                    DataBuffer buffer = factory.allocateBuffer(pcmData.length);
                    try {
                        buffer.write(pcmData);
                        return buffer;
                    } catch (RuntimeException ex) {
                        NettyBufferReleaser.release(buffer);
                        throw ex;
                    }
                });
    }

    private static void validateOperatorId(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operatorId cannot be null or blank");
        }
    }
}
