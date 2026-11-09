package com.voicepulse.gateway.infrastructure.ws;

import com.voicepulse.gateway.domain.model.DisconnectReason;
import com.voicepulse.gateway.domain.ports.inbound.IngestAudioChunkUseCase;
import com.voicepulse.gateway.domain.ports.inbound.ManageCallSessionUseCase;
import com.voicepulse.gateway.infrastructure.registry.ConcurrentSessionRegistry;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallSessionId;
import io.netty.util.ReferenceCountUtil;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Primary inbound reactive WebSocket adapter for continuous telephony audio streaming.
 *
 * <p>Listens at {@code /ws/calls/{callId}/audio}, enforces DoS frame boundaries (&lt;= 64 KB),
 * releases Netty ByteBuf memory deterministically, and dispatches frames to domain use cases.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AudioWebSocketHandler implements WebSocketHandler {

    private static final Pattern PATH_PATTERN = Pattern.compile("^/ws/calls/([^/]+)/audio/?$");

    private final @NonNull IngestAudioChunkUseCase ingestAudioChunkUseCase;
    private final @NonNull ManageCallSessionUseCase manageCallSessionUseCase;
    private final @NonNull ConcurrentSessionRegistry sessionRegistry;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String callIdStr = extractCallId(session);
        if (callIdStr == null) {
            log.warn("WebSocket handshake rejected: invalid path {}", getPath(session));
            return session.close(
                    new CloseStatus(1002, "Invalid path: expected /ws/calls/{callId}/audio"));
        }

        CallSessionId sessionId;
        try {
            sessionId = CallSessionId.fromString(callIdStr);
        } catch (IllegalArgumentException ex) {
            log.warn("WebSocket handshake rejected: malformed UUID callId {}", callIdStr);
            return session.close(
                    new CloseStatus(1007, "Invalid callId format: must be valid UUID"));
        }

        try {
            if (manageCallSessionUseCase.getSession(sessionId).isEmpty()) {
                manageCallSessionUseCase.startSession(sessionId);
            }
        } catch (Exception ex) {
            log.error("Failed to initialize session for callId: {}", sessionId, ex);
            return session.close(CloseStatus.SERVER_ERROR);
        }

        AtomicLong sequenceCounter = new AtomicLong(0L);
        Flux<WebSocketMessage> outboundFlux = sessionRegistry.registerSession(sessionId, session);

        Mono<Void> inbound =
                session.receive()
                        .concatMap(
                                message ->
                                        processMessage(
                                                session, sessionId, sequenceCounter, message))
                        .doOnError(
                                err ->
                                        log.error(
                                                "Inbound pipeline error for callId {}",
                                                sessionId,
                                                err))
                        .then()
                        .doFinally(
                                signalType -> {
                                    handleSessionTermination(sessionId, signalType);
                                    sessionRegistry.unregisterSession(sessionId);
                                });

        Mono<Void> outbound =
                session.send(outboundFlux)
                        .doOnError(
                                err ->
                                        log.error(
                                                "Outbound pipeline error for callId {}",
                                                sessionId,
                                                err));

        return Mono.when(inbound, outbound)
                .doOnError(
                        err -> log.error("WebSocket handler error for callId {}", sessionId, err))
                .doFinally(signalType -> sessionRegistry.unregisterSession(sessionId));
    }

    private Mono<Void> processMessage(
            WebSocketSession session,
            CallSessionId sessionId,
            AtomicLong sequenceCounter,
            WebSocketMessage message) {
        if (message.getType() != WebSocketMessage.Type.BINARY) {
            releaseBuffer(message.getPayload());
            return Mono.empty();
        }

        DataBuffer dataBuffer = message.getPayload();
        DataBufferUtils.retain(dataBuffer);
        int readableBytes = dataBuffer.readableByteCount();

        if (readableBytes > AudioChunk.MAX_CHUNK_SIZE_BYTES) {
            log.warn(
                    "DoS Guard: Frame size exceeds limit: {} bytes > {} bytes",
                    readableBytes,
                    AudioChunk.MAX_CHUNK_SIZE_BYTES);
            releaseBuffer(dataBuffer);
            manageCallSessionUseCase.terminateSession(sessionId, DisconnectReason.PROTOCOL_ERROR);
            return session.close(new CloseStatus(1009, "Payload exceeds 64 KB limit"));
        }

        try {
            byte[] pcmData = new byte[readableBytes];
            dataBuffer.read(pcmData);
            long seq = sequenceCounter.getAndIncrement();
            AudioChunk chunk =
                    new AudioChunk(
                            sessionId, seq, Instant.now(), pcmData, AudioFormat.PCM_16KHZ_MONO);
            ingestAudioChunkUseCase.ingestAudioChunk(chunk);
            return Mono.empty();
        } catch (Exception ex) {
            log.error("Error processing audio frame for session {}", sessionId, ex);
            manageCallSessionUseCase.terminateSession(sessionId, DisconnectReason.PROTOCOL_ERROR);
            return session.close(
                    CloseStatus.SERVER_ERROR.withReason("Audio frame processing failed"));
        } finally {
            releaseBuffer(dataBuffer);
        }
    }

    private void releaseBuffer(DataBuffer dataBuffer) {
        if (dataBuffer == null) {
            return;
        }
        if (dataBuffer instanceof NettyDataBuffer nettyDataBuffer) {
            ReferenceCountUtil.release(nettyDataBuffer.getNativeBuffer());
        } else {
            DataBufferUtils.release(dataBuffer);
        }
    }

    private void handleSessionTermination(CallSessionId sessionId, SignalType signalType) {
        DisconnectReason reason =
                (signalType == SignalType.ON_ERROR)
                        ? DisconnectReason.PROTOCOL_ERROR
                        : DisconnectReason.CLIENT_HANGUP;
        try {
            manageCallSessionUseCase.terminateSession(sessionId, reason);
            log.info(
                    "WebSocket session terminated for callId: {}, signal: {}, reason: {}",
                    sessionId,
                    signalType,
                    reason);
        } catch (Exception ex) {
            log.debug(
                    "Session termination handling for callId {} finished: {}",
                    sessionId,
                    ex.getMessage());
        }
    }

    private String extractCallId(WebSocketSession session) {
        String callId = extractFromAttributes(session.getAttributes());
        if (callId != null) {
            return callId;
        }

        callId = extractFromAttributes(session.getHandshakeInfo().getAttributes());
        if (callId != null) {
            return callId;
        }

        String path = getPath(session);
        if (path != null) {
            Matcher matcher = PATH_PATTERN.matcher(path);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }

        return null;
    }

    private String extractFromAttributes(Map<String, Object> attributes) {
        Object uriVars = attributes.get(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (uriVars instanceof Map<?, ?> map && map.containsKey("callId")) {
            return String.valueOf(map.get("callId"));
        }
        return null;
    }

    private String getPath(WebSocketSession session) {
        URI uri = session.getHandshakeInfo().getUri();
        return uri.getPath();
    }
}
