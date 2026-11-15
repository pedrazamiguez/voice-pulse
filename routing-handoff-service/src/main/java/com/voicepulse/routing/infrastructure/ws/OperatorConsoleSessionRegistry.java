package com.voicepulse.routing.infrastructure.ws;

import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.infrastructure.ws.config.OperatorConsoleProperties;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorAssignmentPayloadDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorWsMessage;
import com.voicepulse.shared.model.CallId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Thread-safe registry tracking active Operator Web Console WebSocket outbound sinks keyed by
 * {@code (OperatorId, connectionId)} and caching each operator's latest active assignment payload
 * for anti-race reconnect snapshot replay.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperatorConsoleSessionRegistry {

    private final @NonNull OperatorConsoleProperties consoleProperties;

    private final ConcurrentHashMap<
                    OperatorId, ConcurrentHashMap<String, Sinks.Many<OperatorWsMessage>>>
            operatorConnections = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<OperatorId, OperatorAssignmentPayloadDto> activeAssignments =
            new ConcurrentHashMap<>();

    /**
     * Registers a new WebSocket connection sink for {@code operatorId} and returns its outbound
     * message flux.
     */
    public Flux<OperatorWsMessage> register(OperatorId operatorId, String connectionId) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(connectionId, "connectionId cannot be null");
        Sinks.Many<OperatorWsMessage> sink =
                Sinks.many()
                        .multicast()
                        .onBackpressureBuffer(consoleProperties.outboundBufferCapacity(), false);

        ConcurrentHashMap<String, Sinks.Many<OperatorWsMessage>> connections =
                operatorConnections.computeIfAbsent(
                        operatorId, ignored -> new ConcurrentHashMap<>());
        Sinks.Many<OperatorWsMessage> previous = connections.put(connectionId, sink);
        if (previous != null) {
            previous.tryEmitComplete();
        }
        return sink.asFlux();
    }

    /** Unregisters and completes the outbound sink for a specific WebSocket connection. */
    public void unregister(OperatorId operatorId, String connectionId) {
        if (operatorId == null || connectionId == null) {
            return;
        }
        operatorConnections.computeIfPresent(
                operatorId,
                (ignored, connections) -> {
                    Sinks.Many<OperatorWsMessage> removed = connections.remove(connectionId);
                    if (removed != null) {
                        synchronized (removed) {
                            removed.tryEmitComplete();
                        }
                    }
                    return connections.isEmpty() ? null : connections;
                });
    }

    /** Emits a signaling message to all active WebSocket connections for {@code operatorId}. */
    public void sendToOperator(OperatorId operatorId, OperatorWsMessage message) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        Map<String, Sinks.Many<OperatorWsMessage>> connections =
                operatorConnections.get(operatorId);
        if (connections == null || connections.isEmpty()) {
            return;
        }
        connections.forEach(
                (connectionId, sink) -> emitToSink(operatorId, connectionId, sink, message));
    }

    /** Emits a signaling message to a single specific WebSocket connection. */
    public void sendToConnection(
            OperatorId operatorId, String connectionId, OperatorWsMessage message) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(connectionId, "connectionId cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        Map<String, Sinks.Many<OperatorWsMessage>> connections =
                operatorConnections.get(operatorId);
        if (connections == null) {
            return;
        }
        Sinks.Many<OperatorWsMessage> sink = connections.get(connectionId);
        if (sink != null) {
            emitToSink(operatorId, connectionId, sink, message);
        }
    }

    /** Broadcasts a signaling message to every currently connected Operator Web Console. */
    public void broadcast(OperatorWsMessage message) {
        Objects.requireNonNull(message, "message cannot be null");
        operatorConnections.forEach(
                (operatorId, connections) ->
                        connections.forEach(
                                (connectionId, sink) ->
                                        emitToSink(operatorId, connectionId, sink, message)));
    }

    /** Caches an active assignment payload so reconnecting consoles immediately replay it. */
    public void cacheActiveAssignment(OperatorId operatorId, OperatorAssignmentPayloadDto payload) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        activeAssignments.put(operatorId, payload);
    }

    /** Returns the cached active assignment payload for an operator if present. */
    public Optional<OperatorAssignmentPayloadDto> getActiveAssignment(OperatorId operatorId) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        return Optional.ofNullable(activeAssignments.get(operatorId));
    }

    /** Evicts an operator's cached active assignment if it matches {@code callId}. */
    public void evictActiveAssignment(OperatorId operatorId, CallId callId) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(callId, "callId cannot be null");
        String targetCallId = callId.toString();
        activeAssignments.computeIfPresent(
                operatorId,
                (ignored, existing) -> targetCallId.equals(existing.callId()) ? null : existing);
    }

    /** Evicts any cached active assignment matching {@code callId} across all operators. */
    public void evictActiveAssignmentByCallId(CallId callId) {
        Objects.requireNonNull(callId, "callId cannot be null");
        String targetCallId = callId.toString();
        activeAssignments
                .entrySet()
                .removeIf(entry -> targetCallId.equals(entry.getValue().callId()));
    }

    /** Returns the number of active WebSocket connections for {@code operatorId}. */
    public int activeConnectionCount(OperatorId operatorId) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Map<String, Sinks.Many<OperatorWsMessage>> connections =
                operatorConnections.get(operatorId);
        return connections == null ? 0 : connections.size();
    }

    /** Returns the total number of active WebSocket connections across all operators. */
    public int totalActiveConnections() {
        return operatorConnections.values().stream().mapToInt(Map::size).sum();
    }

    private void emitToSink(
            OperatorId operatorId,
            String connectionId,
            Sinks.Many<OperatorWsMessage> sink,
            OperatorWsMessage message) {
        Sinks.EmitResult result;
        synchronized (sink) {
            result = sink.tryEmitNext(message);
        }
        if (result.isFailure()) {
            log.debug(
                    "Failed to emit WS message to operatorId={}, connectionId={}, result={}",
                    operatorId,
                    connectionId,
                    result);
            if (result == Sinks.EmitResult.FAIL_TERMINATED
                    || result == Sinks.EmitResult.FAIL_CANCELLED) {
                unregister(operatorId, connectionId);
            }
        }
    }
}
