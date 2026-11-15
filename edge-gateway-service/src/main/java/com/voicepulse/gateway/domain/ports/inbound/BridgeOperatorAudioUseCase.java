package com.voicepulse.gateway.domain.ports.inbound;

import com.voicepulse.gateway.domain.model.BridgeAuthorizationStatus;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;

/** Inbound driving port for managing bot-to-human operator audio bridges. */
public interface BridgeOperatorAudioUseCase {

    /**
     * Activates an audio bridge between an active call session and an assigned human operator.
     *
     * @param sessionId caller session identifier
     * @param operatorId assigned operator identifier
     * @param bridgeEndpoint WebSocket bridge URI
     * @param bridgedAt timestamp when the reroute signal occurred
     */
    void activateBridge(
            CallSessionId sessionId, String operatorId, String bridgeEndpoint, Instant bridgedAt);

    /**
     * Authorizes an operator WebSocket handshake for the specified call session.
     *
     * @param sessionId caller session identifier
     * @param operatorId operator identifier presenting at the WebSocket endpoint
     * @return sealed authorization status
     */
    BridgeAuthorizationStatus authorizeOperatorBridge(CallSessionId sessionId, String operatorId);

    /**
     * Relays raw 16 kHz Linear PCM audio from the authorized operator to the caller session.
     *
     * @param sessionId caller session identifier
     * @param operatorId authorized operator identifier
     * @param pcmData raw PCM frame bytes
     */
    void relayOperatorAudio(CallSessionId sessionId, String operatorId, byte[] pcmData);

    /**
     * Detaches the operator WebSocket endpoint cleanly when the operator stream ends.
     *
     * @param sessionId caller session identifier
     * @param operatorId disconnecting operator identifier
     */
    void handleOperatorDisconnected(CallSessionId sessionId, String operatorId);
}
