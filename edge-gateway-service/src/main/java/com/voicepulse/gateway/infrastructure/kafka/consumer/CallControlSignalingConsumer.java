package com.voicepulse.gateway.infrastructure.kafka.consumer;

import com.voicepulse.gateway.domain.ports.inbound.InterruptPlaybackUseCase;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.CallControlEnvelope;
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

    @KafkaListener(
            topics = "${voicepulse.gateway.kafka.control-topic:telephony.calls.control}",
            groupId = "${voicepulse.gateway.kafka.consumer-group:edge-gateway-control-workers}",
            containerFactory = "callControlKafkaListenerContainerFactory")
    public void onCallControlRecord(ConsumerRecord<String, CallControlEnvelope> record) {
        if (record == null || record.value() == null) {
            return;
        }

        CallControlEnvelope envelope = record.value();
        if (envelope instanceof BargeInInterrupt bargeIn) {
            log.info(
                    "Received BargeInInterrupt: callId={}, seq={}, offsetMs={}, partition={}",
                    bargeIn.callId(),
                    bargeIn.sequenceNumber(),
                    bargeIn.playbackOffsetMs(),
                    record.partition());

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
    }
}
