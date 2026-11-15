package com.voicepulse.gateway.domain.ports.outbound;

import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallSessionId;

/** Outbound driven port for managing bidirectional WebSocket audio bridges. */
public interface OperatorAudioBridgePort {

    /**
     * Registers or activates bridge pairing metadata for a session and operator.
     *
     * @param sessionId caller session identifier
     * @param operatorId assigned operator identifier
     */
    void activateBridge(CallSessionId sessionId, String operatorId);

    /**
     * Forwards a caller PCM audio chunk directly to the paired operator WebSocket endpoint.
     *
     * @param chunk validated caller audio chunk
     */
    void relayCallerAudio(AudioChunk chunk);

    /**
     * Forwards raw operator PCM audio directly to the paired caller WebSocket endpoint.
     *
     * @param sessionId caller session identifier
     * @param operatorId authorized operator identifier
     * @param pcmData raw PCM frame bytes
     */
    void relayOperatorAudio(CallSessionId sessionId, String operatorId, byte[] pcmData);

    /**
     * Detaches the operator WebSocket endpoint from an active bridge.
     *
     * @param sessionId caller session identifier
     * @param operatorId operator identifier
     */
    void detachOperator(CallSessionId sessionId, String operatorId);

    /**
     * Closes and releases all bridge resources associated with a terminated call session.
     *
     * @param sessionId caller session identifier
     */
    void closeBridge(CallSessionId sessionId);
}
