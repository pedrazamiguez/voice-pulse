package com.voicepulse.gateway.infrastructure.registry;

import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.shared.model.CallSessionId;
import io.netty.util.ReferenceCountUtil;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Thread-safe, non-blocking in-memory registry implementing {@link SessionRegistryPort}.
 *
 * <p>Tracks active {@link CallSession} domain aggregates and manages live {@link WebSocketSession}
 * connections alongside outbound reactive message sinks to support bidirectional streaming with
 * backpressure protection and zero direct memory leaks.
 */
@Slf4j
@Component
public final class ConcurrentSessionRegistry implements SessionRegistryPort {

    public static final int DEFAULT_BUFFER_CAPACITY = 8192;

    private final int bufferCapacity;
    private final ConcurrentHashMap<CallSessionId, CallSession> callSessions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CallSessionId, WebSocketSession> webSocketSessions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CallSessionId, Sinks.Many<WebSocketMessage>> outboundSinks =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CallSessionId, Queue<WebSocketMessage>> outboundQueues =
            new ConcurrentHashMap<>();

    public ConcurrentSessionRegistry() {
        this(DEFAULT_BUFFER_CAPACITY);
    }

    public ConcurrentSessionRegistry(int bufferCapacity) {
        if (bufferCapacity <= 0) {
            throw new IllegalArgumentException(
                    "bufferCapacity must be strictly positive, was: " + bufferCapacity);
        }
        this.bufferCapacity = bufferCapacity;
    }

    public int getBufferCapacity() {
        return bufferCapacity;
    }

    // --- Domain SessionRegistryPort Implementation ---

    @Override
    public void save(CallSession session) {
        Objects.requireNonNull(session, "session cannot be null");
        callSessions.put(session.id(), session);
    }

    @Override
    public Optional<CallSession> findById(CallSessionId sessionId) {
        validateSessionId(sessionId);
        return Optional.ofNullable(callSessions.get(sessionId));
    }

    @Override
    public void remove(CallSessionId sessionId) {
        validateSessionId(sessionId);
        callSessions.remove(sessionId);
        unregisterSession(sessionId);
    }

    @Override
    public boolean exists(CallSessionId sessionId) {
        validateSessionId(sessionId);
        return callSessions.containsKey(sessionId);
    }

    @Override
    public int activeSessionCount() {
        return callSessions.size();
    }

    // --- Outbound WebSocket Transport Registry ---

    /**
     * Registers an active WebSocket session and creates a dedicated bounded outbound sink.
     *
     * @param sessionId call identifier
     * @param session live WebSocket session
     * @return outbound flux of WebSocket messages to pass to {@link WebSocketSession#send}
     */
    public Flux<WebSocketMessage> registerSession(
            CallSessionId sessionId, WebSocketSession session) {
        validateSessionId(sessionId);
        Objects.requireNonNull(session, "session cannot be null");

        Queue<WebSocketMessage> queue = new LinkedBlockingQueue<>(bufferCapacity);
        Sinks.Many<WebSocketMessage> sink = Sinks.many().unicast().onBackpressureBuffer(queue);

        webSocketSessions.put(sessionId, session);
        outboundSinks.put(sessionId, sink);
        outboundQueues.put(sessionId, queue);

        log.debug("Registered WebSocket session for callId: {}", sessionId);
        return sink.asFlux();
    }

    /**
     * Unregisters the WebSocket session, purges queued outbound messages, and completes its sink.
     *
     * @param sessionId call identifier
     */
    public void unregisterSession(CallSessionId sessionId) {
        validateSessionId(sessionId);

        webSocketSessions.remove(sessionId);
        Queue<WebSocketMessage> queue = outboundQueues.remove(sessionId);
        if (queue != null) {
            WebSocketMessage message;
            while ((message = queue.poll()) != null) {
                releaseMessagePayload(message);
            }
        }

        Sinks.Many<WebSocketMessage> sink = outboundSinks.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }

        log.debug("Unregistered WebSocket session for callId: {}", sessionId);
    }

    /**
     * Purges and releases all queued outbound messages for the specified call session.
     *
     * <p>Ensures zero direct memory leaks by safely releasing underlying Netty buffers.
     *
     * @param sessionId call identifier
     * @return number of purged messages
     */
    public int purgeOutboundBuffer(CallSessionId sessionId) {
        validateSessionId(sessionId);
        Queue<WebSocketMessage> queue = outboundQueues.get(sessionId);
        if (queue == null) {
            return 0;
        }

        int purgedCount = 0;
        WebSocketMessage message;
        while ((message = queue.poll()) != null) {
            releaseMessagePayload(message);
            purgedCount++;
        }

        log.debug("Purged {} queued outbound messages for callId: {}", purgedCount, sessionId);
        return purgedCount;
    }

    /**
     * Retrieves the active WebSocket session for a call session ID.
     *
     * @param sessionId call identifier
     * @return Optional containing the session if present and active
     */
    public Optional<WebSocketSession> getSession(CallSessionId sessionId) {
        validateSessionId(sessionId);
        return Optional.ofNullable(webSocketSessions.get(sessionId));
    }

    /**
     * Emits a message to the outbound sink associated with the given session ID.
     *
     * @param sessionId call identifier
     * @param message WebSocket message to emit
     * @return true if successfully queued, false otherwise
     */
    public boolean emitMessage(CallSessionId sessionId, WebSocketMessage message) {
        validateSessionId(sessionId);
        Objects.requireNonNull(message, "message cannot be null");

        Sinks.Many<WebSocketMessage> sink = outboundSinks.get(sessionId);
        if (sink == null) {
            releaseMessagePayload(message);
            return false;
        }

        Sinks.EmitResult result = sink.tryEmitNext(message);
        if (result.isFailure()) {
            log.warn(
                    "Failed to emit outbound message for callId {}: emitResult={}",
                    sessionId,
                    result);
            releaseMessagePayload(message);
            return false;
        }

        return true;
    }

    /**
     * Checks if a live WebSocket session is registered for the given ID.
     *
     * @param sessionId call identifier
     * @return true if registered, false otherwise
     */
    public boolean hasWebSocketSession(CallSessionId sessionId) {
        validateSessionId(sessionId);
        return webSocketSessions.containsKey(sessionId);
    }

    /**
     * Returns the count of currently registered WebSocket sessions.
     *
     * @return active WebSocket session count
     */
    public int activeWebSocketSessionCount() {
        return webSocketSessions.size();
    }

    private void validateSessionId(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
    }

    private void releaseMessagePayload(WebSocketMessage message) {
        if (message == null || message.getPayload() == null) {
            return;
        }
        DataBuffer dataBuffer = message.getPayload();
        if (dataBuffer instanceof NettyDataBuffer nettyDataBuffer) {
            ReferenceCountUtil.release(nettyDataBuffer.getNativeBuffer());
        } else {
            DataBufferUtils.release(dataBuffer);
        }
    }
}
