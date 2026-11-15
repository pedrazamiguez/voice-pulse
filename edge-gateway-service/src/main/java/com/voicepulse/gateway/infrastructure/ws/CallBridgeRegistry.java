package com.voicepulse.gateway.infrastructure.ws;

import com.voicepulse.gateway.infrastructure.config.GatewayBridgeProperties;
import com.voicepulse.shared.model.CallSessionId;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Sinks;

/**
 * Thread-safe registry pairing bridged {@link CallSessionId} instances with their assigned operator
 * identifiers and active {@link OperatorAudioEndpoint} WebSocket transport handles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallBridgeRegistry {

    private static final String SESSION_ID_NULL_MSG = "sessionId cannot be null";

    private final @NonNull GatewayBridgeProperties properties;

    private final ConcurrentHashMap<CallSessionId, String> activeAssignments =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CallSessionId, OperatorAudioEndpoint> connectedOperators =
            new ConcurrentHashMap<>();

    /**
     * Records an active operator assignment for the given call session.
     *
     * @param sessionId caller session identifier
     * @param operatorId assigned operator identifier
     */
    public void activateBridge(CallSessionId sessionId, String operatorId) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        validateOperatorId(operatorId);
        activeAssignments.put(sessionId, operatorId);
        log.debug(
                "Registered active bridge assignment: callId={}, operatorId={}",
                sessionId,
                operatorId);
    }

    /**
     * Registers a live operator WebSocket session for an actively assigned bridge.
     *
     * @param sessionId caller session identifier
     * @param operatorId authorized operator identifier
     * @param session live WebSocket session
     * @return registered endpoint, or empty if the bridge was closed concurrently or mismatched
     */
    public Optional<OperatorAudioEndpoint> registerOperatorEndpoint(
            CallSessionId sessionId, String operatorId, WebSocketSession session) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        validateOperatorId(operatorId);
        Objects.requireNonNull(session, "session cannot be null");

        if (!operatorId.equals(activeAssignments.get(sessionId))) {
            log.warn(
                    "Rejected endpoint registration without matching active assignment: callId={},"
                            + " operatorId={}",
                    sessionId,
                    operatorId);
            return Optional.empty();
        }

        OperatorAudioEndpoint endpoint =
                new OperatorAudioEndpoint(
                        sessionId, operatorId, session, properties.outboundBufferCapacity());

        OperatorAudioEndpoint previous = connectedOperators.put(sessionId, endpoint);
        if (previous != null) {
            previous.drainAndComplete();
        }

        if (!operatorId.equals(activeAssignments.get(sessionId))) {
            connectedOperators.remove(sessionId, endpoint);
            endpoint.drainAndComplete();
            return Optional.empty();
        }

        log.debug(
                "Connected operator WebSocket endpoint: callId={}, operatorId={}",
                sessionId,
                operatorId);
        return Optional.of(endpoint);
    }

    /**
     * Returns the connected operator WebSocket session if currently open.
     *
     * @param sessionId caller session identifier
     * @return connected operator WebSocket session if present
     */
    public Optional<WebSocketSession> getConnectedSession(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        OperatorAudioEndpoint endpoint = connectedOperators.get(sessionId);
        return endpoint != null ? Optional.of(endpoint.session()) : Optional.empty();
    }

    /**
     * Returns the assigned operator identifier for an active bridge if present.
     *
     * @param sessionId caller session identifier
     * @return assigned operator identifier
     */
    public Optional<String> getAssignedOperatorId(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        return Optional.ofNullable(activeAssignments.get(sessionId));
    }

    /**
     * Emits an outbound binary message to the paired operator WebSocket sink, deterministically
     * releasing the payload buffer if delivery cannot occur.
     *
     * @param sessionId caller session identifier
     * @param message WebSocket message carrying PCM audio
     * @return true if accepted into the outbound sink, false otherwise
     */
    public boolean emitMessage(CallSessionId sessionId, WebSocketMessage message) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        Objects.requireNonNull(message, "message cannot be null");

        OperatorAudioEndpoint endpoint = connectedOperators.get(sessionId);
        if (endpoint == null || !endpoint.session().isOpen()) {
            NettyBufferReleaser.release(message);
            return false;
        }

        Sinks.EmitResult result = endpoint.tryEmitNext(message);
        if (result.isFailure()) {
            log.warn(
                    "Failed to emit caller audio frame to operator bridge: callId={},"
                            + " operatorId={}, emitResult={}",
                    sessionId,
                    endpoint.operatorId(),
                    result);
            NettyBufferReleaser.release(message);
            return false;
        }
        return true;
    }

    /**
     * Detaches the connected operator endpoint without tearing down the call session assignment.
     *
     * @param sessionId caller session identifier
     * @param operatorId operator identifier disconnecting
     */
    public void detachOperator(CallSessionId sessionId, String operatorId) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        validateOperatorId(operatorId);

        connectedOperators.computeIfPresent(
                sessionId,
                (id, existing) -> {
                    if (existing.operatorId().equals(operatorId)) {
                        existing.drainAndComplete();
                        log.debug(
                                "Detached operator WebSocket endpoint: callId={}, operatorId={}",
                                sessionId,
                                operatorId);
                        return null;
                    }
                    return existing;
                });
    }

    /**
     * Tears down bridge assignment and releases any queued outbound operator buffers.
     *
     * @param sessionId caller session identifier
     */
    public void closeBridge(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        activeAssignments.remove(sessionId);
        OperatorAudioEndpoint removed = connectedOperators.remove(sessionId);
        if (removed != null) {
            removed.drainAndComplete();
            log.debug(
                    "Closed audio bridge: callId={}, operatorId={}",
                    sessionId,
                    removed.operatorId());
        }
    }

    private static void validateOperatorId(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operatorId cannot be null or blank");
        }
    }
}
