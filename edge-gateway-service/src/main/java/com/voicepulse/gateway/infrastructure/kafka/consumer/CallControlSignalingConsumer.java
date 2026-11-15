package com.voicepulse.gateway.infrastructure.kafka.consumer;

import com.voicepulse.gateway.domain.ports.inbound.BridgeOperatorAudioUseCase;
import com.voicepulse.gateway.domain.ports.inbound.InterruptPlaybackUseCase;
import com.voicepulse.shared.event.BackpressureSignal;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.event.CallStarted;
import com.voicepulse.shared.event.CallTerminated;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import com.voicepulse.shared.model.CallSessionId;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Primary inbound Kafka adapter listening on {@code telephony.calls.control}.
 *
 * <p>Consumes signaling envelopes and routes barge-in interruption events to {@link
 * InterruptPlaybackUseCase} for immediate sub-50ms buffer purging and WebSocket notification.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallControlSignalingConsumer {

    private final @NonNull InterruptPlaybackUseCase interruptPlaybackUseCase;
    private final @NonNull BridgeOperatorAudioUseCase bridgeOperatorAudioUseCase;

    @KafkaListener(
            topics = "${voicepulse.gateway.kafka.control-topic:telephony.calls.control}",
            groupId = "${voicepulse.gateway.kafka.consumer-group:edge-gateway-control-workers}",
            containerFactory = "callControlKafkaListenerContainerFactory")
    public void onCallControlRecord(ConsumerRecord<String, CallControlEnvelope> record) {
        if (record == null || record.value() == null) {
            return;
        }

        CallControlEnvelope envelope = record.value();
        switch (envelope) {
            case BargeInInterrupt bargeIn -> handleBargeIn(bargeIn, record.partition());
            case OperatorRerouteSignal reroute ->
                    handleOperatorReroute(reroute, record.partition());
            case CallStarted started ->
                    log.debug(
                            "Ignoring CallStarted control event: callId={}, seq={}",
                            started.callId(),
                            started.sequenceNumber());
            case CallTerminated terminated ->
                    log.debug(
                            "Ignoring CallTerminated control event: callId={}, seq={}",
                            terminated.callId(),
                            terminated.sequenceNumber());
            case BackpressureSignal backpressure ->
                    log.debug(
                            "Ignoring BackpressureSignal control event: callId={}, seq={}",
                            backpressure.callId(),
                            backpressure.sequenceNumber());
        }
    }

    private void handleBargeIn(BargeInInterrupt bargeIn, int partition) {
        log.info(
                "Received BargeInInterrupt: callId={}, seq={}, offsetMs={}, partition={}",
                bargeIn.callId(),
                bargeIn.sequenceNumber(),
                bargeIn.playbackOffsetMs(),
                partition);

        CallSessionId sessionId = CallSessionId.fromCallId(bargeIn.callId());
        try {
            interruptPlaybackUseCase.interruptPlayback(sessionId);
        } catch (Exception ex) {
            log.error(
                    "Failed to interrupt playback for session {}: {}",
                    sessionId,
                    ex.getMessage(),
                    ex);
        }
    }

    private void handleOperatorReroute(OperatorRerouteSignal reroute, int partition) {
        log.info(
                "Received OperatorRerouteSignal: callId={}, operatorId={}, endpoint={}, seq={},"
                        + " partition={}",
                reroute.callId(),
                reroute.operatorId(),
                reroute.bridgeEndpoint(),
                reroute.sequenceNumber(),
                partition);

        CallSessionId sessionId = CallSessionId.fromCallId(reroute.callId());
        try {
            bridgeOperatorAudioUseCase.activateBridge(
                    sessionId, reroute.operatorId(), reroute.bridgeEndpoint(), reroute.timestamp());
        } catch (Exception ex) {
            log.error(
                    "Failed to activate operator audio bridge for session {}: {}",
                    sessionId,
                    ex.getMessage(),
                    ex);
        }
    }
}
