package com.voicepulse.gateway.infrastructure.ws;

import com.voicepulse.shared.model.CallSessionId;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Active WebSocket transport endpoint paired to a bridged call session for an assigned human
 * operator. Encapsulates the bounded outbound queue and {@link Sinks.Many} to serialize emissions
 * and drain pending Netty buffers safely on close.
 */
public final class OperatorAudioEndpoint {

    private final CallSessionId sessionId;
    private final String operatorId;
    private final WebSocketSession session;
    private final Queue<WebSocketMessage> outboundQueue;
    private final Sinks.Many<WebSocketMessage> outboundSink;

    public OperatorAudioEndpoint(
            CallSessionId sessionId,
            String operatorId,
            WebSocketSession session,
            int outboundBufferCapacity) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null");
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operatorId cannot be null or blank");
        }
        if (outboundBufferCapacity <= 0) {
            throw new IllegalArgumentException("outboundBufferCapacity must be positive");
        }
        this.operatorId = operatorId;
        this.session = Objects.requireNonNull(session, "session cannot be null");
        this.outboundQueue = new ArrayBlockingQueue<>(outboundBufferCapacity);
        this.outboundSink = Sinks.many().unicast().onBackpressureBuffer(this.outboundQueue);
    }

    public CallSessionId sessionId() {
        return sessionId;
    }

    public String operatorId() {
        return operatorId;
    }

    public WebSocketSession session() {
        return session;
    }

    /**
     * Emits a message to the outbound operator stream in a producer-serialized manner.
     *
     * @param message WebSocket message to enqueue
     * @return Reactor sink emission status
     */
    public Sinks.EmitResult tryEmitNext(WebSocketMessage message) {
        Objects.requireNonNull(message, "message cannot be null");
        synchronized (outboundSink) {
            return outboundSink.tryEmitNext(message);
        }
    }

    /** Completes the outbound sink and releases every buffered Netty payload buffer. */
    public void drainAndComplete() {
        synchronized (outboundSink) {
            outboundSink.tryEmitComplete();
            WebSocketMessage queued;
            while ((queued = outboundQueue.poll()) != null) {
                NettyBufferReleaser.release(queued);
            }
        }
    }

    /**
     * Exposes the outbound message stream with deterministic Netty buffer discard cleanup.
     *
     * @return outbound WebSocket message flux
     */
    public Flux<WebSocketMessage> outboundFlux() {
        return outboundSink
                .asFlux()
                .doOnDiscard(WebSocketMessage.class, NettyBufferReleaser::release);
    }
}
