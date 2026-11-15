package com.voicepulse.gateway.infrastructure.kafka.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.voicepulse.gateway.domain.ports.inbound.BridgeOperatorAudioUseCase;
import com.voicepulse.gateway.domain.ports.inbound.InterruptPlaybackUseCase;
import com.voicepulse.shared.event.BackpressureSignal;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.event.CallStarted;
import com.voicepulse.shared.event.CallTerminated;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CallControlSignalingConsumer OperatorReroute & Envelope Routing Tests")
class CallControlSignalingConsumerBridgeTest {

    private static final String TOPIC = "telephony.calls.control";

    @Mock private InterruptPlaybackUseCase interruptPlaybackUseCase;

    @Mock private BridgeOperatorAudioUseCase bridgeOperatorAudioUseCase;

    @InjectMocks private CallControlSignalingConsumer consumer;

    @Test
    @DisplayName("delegates OperatorRerouteSignal to BridgeOperatorAudioUseCase activateBridge")
    void delegatesOperatorRerouteSignal() {
        CallId callId = CallId.generate();
        Instant ts = Instant.now();
        String endpoint = "/ws/operators/op-15/calls/" + callId + "/audio";
        OperatorRerouteSignal signal = new OperatorRerouteSignal(callId, 4L, ts, "op-15", endpoint);

        consumer.onCallControlRecord(new ConsumerRecord<>(TOPIC, 0, 1L, callId.toString(), signal));

        verify(bridgeOperatorAudioUseCase)
                .activateBridge(CallSessionId.fromCallId(callId), "op-15", endpoint, ts);
        verifyNoInteractions(interruptPlaybackUseCase);
    }

    @Test
    @DisplayName("isolates exceptions thrown by activateBridge so Kafka listener does not crash")
    void isolatesActivateBridgeExceptions() {
        CallId callId = CallId.generate();
        OperatorRerouteSignal signal =
                new OperatorRerouteSignal(
                        callId,
                        2L,
                        Instant.now(),
                        "op-1",
                        "/ws/operators/op-1/calls/" + callId + "/audio");
        doThrow(new IllegalStateException("closed"))
                .when(bridgeOperatorAudioUseCase)
                .activateBridge(any(), any(), any(), any());

        consumer.onCallControlRecord(new ConsumerRecord<>(TOPIC, 0, 2L, callId.toString(), signal));

        verify(bridgeOperatorAudioUseCase).activateBridge(any(), any(), any(), any());
    }

    @Test
    @DisplayName("handles non-actionable CallStarted, CallTerminated, BackpressureSignal safely")
    void ignoresNonGatewayActionableEnvelopes() {
        CallId callId = CallId.generate();
        Instant ts = Instant.now();

        CallControlEnvelope[] ignoredEvents = {
            new CallStarted(callId, 1L, ts, AudioFormat.PCM_16KHZ_MONO),
            new CallTerminated(callId, 2L, ts, "NORMAL"),
            new BackpressureSignal(callId, 3L, ts, true, 90)
        };
        for (CallControlEnvelope event : ignoredEvents) {
            consumer.onCallControlRecord(
                    new ConsumerRecord<>(
                            TOPIC, 0, event.sequenceNumber(), callId.toString(), event));
        }

        verifyNoInteractions(interruptPlaybackUseCase, bridgeOperatorAudioUseCase);
    }
}
