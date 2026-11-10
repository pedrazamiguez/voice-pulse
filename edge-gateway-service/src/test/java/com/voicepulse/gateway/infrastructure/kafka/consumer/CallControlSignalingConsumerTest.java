package com.voicepulse.gateway.infrastructure.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.voicepulse.gateway.domain.ports.inbound.InterruptPlaybackUseCase;
import com.voicepulse.shared.event.BackpressureSignal;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.CallControlEnvelope;
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
@DisplayName("CallControlSignalingConsumer Unit Tests")
class CallControlSignalingConsumerTest {

    private static final String TOPIC = "telephony.calls.control";

    @Mock private InterruptPlaybackUseCase interruptPlaybackUseCase;

    @InjectMocks private CallControlSignalingConsumer consumer;

    @Test
    @DisplayName("onCallControlRecord delegates BargeInInterrupt to InterruptPlaybackUseCase")
    void delegatesBargeInInterrupt() {
        CallId callId = CallId.generate();
        CallSessionId sessionId = CallSessionId.fromCallId(callId);
        BargeInInterrupt interrupt = new BargeInInterrupt(callId, 10L, Instant.now(), 50L);
        ConsumerRecord<String, CallControlEnvelope> record =
                new ConsumerRecord<>(TOPIC, 0, 100L, callId.toString(), interrupt);

        consumer.onCallControlRecord(record);

        verify(interruptPlaybackUseCase).interruptPlayback(sessionId);
    }

    @Test
    @DisplayName("onCallControlRecord ignores non-barge-in control envelopes")
    void ignoresNonBargeInEnvelopes() {
        CallId callId = CallId.generate();
        BackpressureSignal backpressure =
                new BackpressureSignal(callId, 5L, Instant.now(), true, 85);
        ConsumerRecord<String, CallControlEnvelope> record =
                new ConsumerRecord<>(TOPIC, 0, 101L, callId.toString(), backpressure);

        consumer.onCallControlRecord(record);

        verify(interruptPlaybackUseCase, never()).interruptPlayback(any());
    }

    @Test
    @DisplayName("onCallControlRecord handles null record or null value safely")
    void handlesNullRecordSafely() {
        assertThatCode(() -> consumer.onCallControlRecord(null)).doesNotThrowAnyException();

        ConsumerRecord<String, CallControlEnvelope> nullValueRecord =
                new ConsumerRecord<>(TOPIC, 0, 102L, "key", null);
        assertThatCode(() -> consumer.onCallControlRecord(nullValueRecord))
                .doesNotThrowAnyException();

        verify(interruptPlaybackUseCase, never()).interruptPlayback(any());
    }

    @Test
    @DisplayName(
            "onCallControlRecord catches and logs exceptions from use case without propagating")
    void handlesUseCaseExceptionGracefully() {
        CallId callId = CallId.generate();
        CallSessionId sessionId = CallSessionId.fromCallId(callId);
        BargeInInterrupt interrupt = new BargeInInterrupt(callId, 1L, Instant.now());
        ConsumerRecord<String, CallControlEnvelope> record =
                new ConsumerRecord<>(TOPIC, 0, 103L, callId.toString(), interrupt);

        doThrow(new RuntimeException("Simulated use case failure"))
                .when(interruptPlaybackUseCase)
                .interruptPlayback(sessionId);

        assertThatCode(() -> consumer.onCallControlRecord(record)).doesNotThrowAnyException();
    }
}
