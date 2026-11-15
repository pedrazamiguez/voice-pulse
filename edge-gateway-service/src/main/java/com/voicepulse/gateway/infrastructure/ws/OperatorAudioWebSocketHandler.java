package com.voicepulse.gateway.infrastructure.ws;

import com.voicepulse.gateway.domain.model.BridgeAuthorizationStatus;
import com.voicepulse.gateway.domain.ports.inbound.BridgeOperatorAudioUseCase;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallSessionId;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * Primary reactive WebSocket handler serving human operator audio bridge endpoints at {@code
 * /ws/operators/{operatorId}/calls/{callId}/audio}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperatorAudioWebSocketHandler implements WebSocketHandler {

    private static final CloseStatus TRY_AGAIN_LATER =
            new CloseStatus(1013, "Try Again Later - Bridge reroute pending");
    private static final CloseStatus PAYLOAD_TOO_LARGE =
            new CloseStatus(1009, "Payload exceeds 64 KB limit");

    private final @NonNull BridgeOperatorAudioUseCase bridgeOperatorAudioUseCase;
    private final @NonNull CallBridgeRegistry callBridgeRegistry;
    private final @NonNull OperatorAudioUriParser uriParser;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Optional<OperatorAudioTarget> parsedTarget = uriParser.parse(session);
        if (parsedTarget.isEmpty()) {
            log.warn(
                    "Rejected operator audio WebSocket with invalid URI: {}",
                    session.getHandshakeInfo().getUri());
            return session.close(
                    CloseStatus.POLICY_VIOLATION.withReason("Invalid operator audio bridge URI"));
        }

        OperatorAudioTarget target = parsedTarget.get();
        CallSessionId sessionId = target.sessionId();
        String operatorId = target.operatorId();

        BridgeAuthorizationStatus status =
                bridgeOperatorAudioUseCase.authorizeOperatorBridge(sessionId, operatorId);
        return switch (status) {
            case BridgeAuthorizationStatus.Authorized ignored ->
                    attachOperatorBridge(session, sessionId, operatorId);
            case BridgeAuthorizationStatus.PendingReroute ignored -> {
                log.debug(
                        "Rejecting premature operator audio connection: callId={}, operatorId={}",
                        sessionId,
                        operatorId);
                yield session.close(TRY_AGAIN_LATER);
            }
            case BridgeAuthorizationStatus.UnauthorizedOperator unauthorized -> {
                log.warn(
                        "Rejected unauthorized operator bridge connection: callId={},"
                                + " requestedOperator={}, assignedOperator={}",
                        sessionId,
                        unauthorized.requestedOperatorId(),
                        unauthorized.assignedOperatorId());
                yield session.close(
                        CloseStatus.POLICY_VIOLATION.withReason("Unauthorized operator for call"));
            }
            case BridgeAuthorizationStatus.SessionUnavailable ignored -> {
                log.warn(
                        "Rejected operator bridge connection for unavailable call session:"
                                + " callId={}, operatorId={}",
                        sessionId,
                        operatorId);
                yield session.close(
                        CloseStatus.POLICY_VIOLATION.withReason("Call session unavailable"));
            }
        };
    }

    private Mono<Void> attachOperatorBridge(
            WebSocketSession session, CallSessionId sessionId, String operatorId) {
        Optional<OperatorAudioEndpoint> registered =
                callBridgeRegistry.registerOperatorEndpoint(sessionId, operatorId, session);
        if (registered.isEmpty()) {
            log.warn(
                    "Failed to register operator WebSocket endpoint cleanly: callId={},"
                            + " operatorId={}",
                    sessionId,
                    operatorId);
            return session.close(
                    CloseStatus.POLICY_VIOLATION.withReason("Call bridge is no longer active"));
        }

        OperatorAudioEndpoint endpoint = registered.get();
        log.info(
                "Operator audio bridge WebSocket connected: callId={}, operatorId={}",
                sessionId,
                operatorId);

        Mono<Void> inbound =
                session.receive()
                        .concatMap(
                                message -> processMessage(session, sessionId, operatorId, message))
                        .doOnError(
                                ex ->
                                        log.error(
                                                "Operator audio WebSocket inbound error: callId={},"
                                                        + " operatorId={}",
                                                sessionId,
                                                operatorId,
                                                ex))
                        .then();

        Mono<Void> outbound =
                session.send(endpoint.outboundFlux())
                        .doOnError(
                                ex ->
                                        log.debug(
                                                "Operator audio WebSocket outbound terminated:"
                                                        + " callId={}, operatorId={}, error={}",
                                                sessionId,
                                                operatorId,
                                                ex.getMessage()));

        return Mono.firstWithSignal(inbound, outbound)
                .doFinally(
                        signal -> {
                            bridgeOperatorAudioUseCase.handleOperatorDisconnected(
                                    sessionId, operatorId);
                            log.info(
                                    "Operator audio bridge WebSocket closed: callId={},"
                                            + " operatorId={}, signal={}",
                                    sessionId,
                                    operatorId,
                                    signal);
                        });
    }

    private Mono<Void> processMessage(
            WebSocketSession session,
            CallSessionId sessionId,
            String operatorId,
            WebSocketMessage message) {
        if (message.getType() != WebSocketMessage.Type.BINARY) {
            NettyBufferReleaser.release(message);
            return Mono.empty();
        }

        DataBuffer dataBuffer = message.getPayload();
        DataBufferUtils.retain(dataBuffer);
        int readableBytes = dataBuffer.readableByteCount();

        if (readableBytes > AudioChunk.MAX_CHUNK_SIZE_BYTES) {
            log.warn(
                    "DoS Guard: Operator audio frame exceeds limit: callId={}, operatorId={},"
                            + " bytes={} > {}",
                    sessionId,
                    operatorId,
                    readableBytes,
                    AudioChunk.MAX_CHUNK_SIZE_BYTES);
            NettyBufferReleaser.release(dataBuffer);
            return session.close(PAYLOAD_TOO_LARGE);
        }

        try {
            byte[] pcmData = new byte[readableBytes];
            dataBuffer.read(pcmData);
            bridgeOperatorAudioUseCase.relayOperatorAudio(sessionId, operatorId, pcmData);
            return Mono.empty();
        } catch (Exception ex) {
            log.error(
                    "Error relaying operator audio frame: callId={}, operatorId={}",
                    sessionId,
                    operatorId,
                    ex);
            return session.close(
                    CloseStatus.SERVER_ERROR.withReason("Operator audio relay failed"));
        } finally {
            NettyBufferReleaser.release(dataBuffer);
        }
    }
}
