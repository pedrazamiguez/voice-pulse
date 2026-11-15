package com.voicepulse.gateway.domain.service;

import com.voicepulse.gateway.domain.exception.CallSessionNotFoundException;
import com.voicepulse.gateway.domain.exception.IllegalSessionStateException;
import com.voicepulse.gateway.domain.model.AudioFrameValidator;
import com.voicepulse.gateway.domain.model.BridgeAuthorizationStatus;
import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.gateway.domain.model.CallSessionState;
import com.voicepulse.gateway.domain.ports.inbound.BridgeOperatorAudioUseCase;
import com.voicepulse.gateway.domain.ports.outbound.AudioPlaybackControlPort;
import com.voicepulse.gateway.domain.ports.outbound.OperatorAudioBridgePort;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure domain service orchestrating caller-to-human operator bridge transitions, authorization, and
 * bidirectional audio relay.
 */
public class OperatorAudioBridgeService implements BridgeOperatorAudioUseCase {

    private static final String SESSION_ID_NULL_MSG = "sessionId cannot be null";

    private final SessionRegistryPort sessionRegistry;
    private final AudioPlaybackControlPort audioPlaybackControlPort;
    private final OperatorAudioBridgePort operatorAudioBridgePort;

    public OperatorAudioBridgeService(
            SessionRegistryPort sessionRegistry,
            AudioPlaybackControlPort audioPlaybackControlPort,
            OperatorAudioBridgePort operatorAudioBridgePort) {
        this.sessionRegistry =
                Objects.requireNonNull(sessionRegistry, "sessionRegistry cannot be null");
        this.audioPlaybackControlPort =
                Objects.requireNonNull(
                        audioPlaybackControlPort, "audioPlaybackControlPort cannot be null");
        this.operatorAudioBridgePort =
                Objects.requireNonNull(
                        operatorAudioBridgePort, "operatorAudioBridgePort cannot be null");
    }

    @Override
    public void activateBridge(
            CallSessionId sessionId, String operatorId, String bridgeEndpoint, Instant bridgedAt) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        validateOperatorId(operatorId);
        if (bridgeEndpoint == null || bridgeEndpoint.isBlank()) {
            throw new IllegalArgumentException("bridgeEndpoint cannot be null or blank");
        }
        Objects.requireNonNull(bridgedAt, "bridgedAt cannot be null");

        CallSession session =
                sessionRegistry
                        .findById(sessionId)
                        .orElseThrow(() -> new CallSessionNotFoundException(sessionId));

        session.rerouteToOperator(operatorId, bridgeEndpoint, bridgedAt);
        sessionRegistry.save(session);
        audioPlaybackControlPort.interruptPlayback(sessionId);
        operatorAudioBridgePort.activateBridge(sessionId, operatorId);
    }

    @Override
    public BridgeAuthorizationStatus authorizeOperatorBridge(
            CallSessionId sessionId, String operatorId) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        validateOperatorId(operatorId);

        return sessionRegistry
                .findById(sessionId)
                .map(session -> evaluateAuthorization(sessionId, operatorId, session.state()))
                .orElseGet(() -> new BridgeAuthorizationStatus.SessionUnavailable(sessionId));
    }

    @Override
    public void relayOperatorAudio(CallSessionId sessionId, String operatorId, byte[] pcmData) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        validateOperatorId(operatorId);
        AudioFrameValidator.validateRawPayload(pcmData);

        CallSession session =
                sessionRegistry
                        .findById(sessionId)
                        .orElseThrow(() -> new CallSessionNotFoundException(sessionId));

        if (!(session.state() instanceof CallSessionState.Bridged bridged)) {
            throw new IllegalSessionStateException(
                    "Cannot relay operator audio for session "
                            + sessionId
                            + " in state "
                            + session.state().getClass().getSimpleName());
        }
        if (!bridged.operatorId().equals(operatorId)) {
            throw new IllegalSessionStateException(
                    "Operator " + operatorId + " is not authorized for session " + sessionId);
        }

        operatorAudioBridgePort.relayOperatorAudio(sessionId, operatorId, pcmData);
    }

    @Override
    public void handleOperatorDisconnected(CallSessionId sessionId, String operatorId) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        validateOperatorId(operatorId);
        operatorAudioBridgePort.detachOperator(sessionId, operatorId);
    }

    private static BridgeAuthorizationStatus evaluateAuthorization(
            CallSessionId sessionId, String requestedOperatorId, CallSessionState state) {
        return switch (state) {
            case CallSessionState.Connected ignored ->
                    new BridgeAuthorizationStatus.PendingReroute(sessionId);
            case CallSessionState.Streaming ignored ->
                    new BridgeAuthorizationStatus.PendingReroute(sessionId);
            case CallSessionState.Disconnected ignored ->
                    new BridgeAuthorizationStatus.SessionUnavailable(sessionId);
            case CallSessionState.Bridged bridged ->
                    bridged.operatorId().equals(requestedOperatorId)
                            ? new BridgeAuthorizationStatus.Authorized(
                                    sessionId, requestedOperatorId)
                            : new BridgeAuthorizationStatus.UnauthorizedOperator(
                                    sessionId, requestedOperatorId, bridged.operatorId());
        };
    }

    private static void validateOperatorId(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operatorId cannot be null or blank");
        }
    }
}
