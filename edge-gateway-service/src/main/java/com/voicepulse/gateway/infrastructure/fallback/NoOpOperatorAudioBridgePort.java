package com.voicepulse.gateway.infrastructure.fallback;

import com.voicepulse.gateway.domain.ports.outbound.OperatorAudioBridgePort;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallSessionId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * Null Object fallback implementation of {@link OperatorAudioBridgePort} active when no concrete
 * transport bridge bean is registered in the application context.
 */
@Slf4j
@Fallback
@Component
public class NoOpOperatorAudioBridgePort implements OperatorAudioBridgePort {

    @Override
    public void activateBridge(CallSessionId sessionId, String operatorId) {
        log.debug(
                "NoOp operator audio bridge activateBridge invoked: callId={}, operatorId={}",
                sessionId,
                operatorId);
    }

    @Override
    public void relayCallerAudio(AudioChunk chunk) {
        log.debug(
                "NoOp operator audio bridge relayCallerAudio invoked: callId={}, seq={}",
                chunk.sessionId(),
                chunk.sequenceNumber());
    }

    @Override
    public void relayOperatorAudio(CallSessionId sessionId, String operatorId, byte[] pcmData) {
        log.debug(
                "NoOp operator audio bridge relayOperatorAudio invoked: callId={}, operatorId={},"
                        + " bytes={}",
                sessionId,
                operatorId,
                pcmData.length);
    }

    @Override
    public void detachOperator(CallSessionId sessionId, String operatorId) {
        log.debug(
                "NoOp operator audio bridge detachOperator invoked: callId={}, operatorId={}",
                sessionId,
                operatorId);
    }

    @Override
    public void closeBridge(CallSessionId sessionId) {
        log.debug("NoOp operator audio bridge closeBridge invoked: callId={}", sessionId);
    }
}
