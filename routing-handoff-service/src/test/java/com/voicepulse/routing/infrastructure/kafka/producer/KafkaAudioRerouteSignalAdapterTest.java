package com.voicepulse.routing.infrastructure.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import com.voicepulse.routing.infrastructure.kafka.config.RoutingKafkaProperties;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import com.voicepulse.shared.model.CallId;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaAudioRerouteSignalAdapter Unit Tests")
class KafkaAudioRerouteSignalAdapterTest {

    private static final String CONTROL_TOPIC = "telephony.calls.control";
    private static final Instant NOW = Instant.parse("2026-11-14T16:15:00Z");

    @Mock private KafkaTemplate<String, CallControlEnvelope> kafkaTemplate;

    @Captor private ArgumentCaptor<ProducerRecord<String, CallControlEnvelope>> recordCaptor;

    private KafkaAudioRerouteSignalAdapter adapter;

    @BeforeEach
    void setUp() {
        RoutingProperties routingProperties =
                new RoutingProperties(
                        "telephony.tickets.intent",
                        CONTROL_TOPIC,
                        "routing-handoff-workers",
                        Duration.ofSeconds(30),
                        "/ws/operators",
                        1000);
        RoutingKafkaProperties kafkaProperties =
                new RoutingKafkaProperties(
                        true, 3, "earliest", Duration.ofMillis(250), "telephony.calls.dlq");
        adapter =
                new KafkaAudioRerouteSignalAdapter(
                        kafkaTemplate, routingProperties, kafkaProperties);
    }

    @Test
    @DisplayName("Should publish OperatorRerouteSignal strictly keyed by callId.toString()")
    void shouldPublishOperatorRerouteSignalKeyedByCallId() {
        CallId callId = CallId.generate();
        OperatorRerouteSignal signal =
                new OperatorRerouteSignal(
                        callId, 1L, NOW, "op-1", "/ws/operators/op-1/calls/" + callId + "/audio");
        ProducerRecord<String, CallControlEnvelope> metadataRecord =
                new ProducerRecord<>(CONTROL_TOPIC, callId.toString(), signal);
        RecordMetadata metadata =
                new RecordMetadata(new TopicPartition(CONTROL_TOPIC, 4), 0L, 42, 0L, 0, 0);
        SendResult<String, CallControlEnvelope> sendResult =
                new SendResult<>(metadataRecord, metadata);

        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        adapter.sendRerouteSignal(signal);

        verify(kafkaTemplate).send(recordCaptor.capture());
        ProducerRecord<String, CallControlEnvelope> sentRecord = recordCaptor.getValue();
        assertThat(sentRecord.topic()).isEqualTo(CONTROL_TOPIC);
        assertThat(sentRecord.key()).isEqualTo(callId.toString());
        assertThat(sentRecord.value()).isEqualTo(signal);
    }

    @Test
    @DisplayName("Should throw IllegalStateException when Kafka publish fails")
    void shouldThrowIllegalStateOnBrokerFailure() {
        CallId callId = CallId.generate();
        OperatorRerouteSignal signal =
                new OperatorRerouteSignal(
                        callId, 2L, NOW, "op-2", "/ws/operators/op-2/calls/" + callId + "/audio");
        CompletableFuture<SendResult<String, CallControlEnvelope>> failed =
                CompletableFuture.failedFuture(new RuntimeException("Broker down"));

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed);

        assertThatThrownBy(() -> adapter.sendRerouteSignal(signal))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(ExecutionException.class)
                .hasMessageContaining(callId.toString());
    }

    @Test
    @DisplayName("Should throw IllegalStateException when Kafka publish times out")
    void shouldThrowIllegalStateOnSendTimeout() {
        CallId callId = CallId.generate();
        OperatorRerouteSignal signal =
                new OperatorRerouteSignal(
                        callId, 3L, NOW, "op-3", "/ws/operators/op-3/calls/" + callId + "/audio");
        CompletableFuture<SendResult<String, CallControlEnvelope>> incomplete =
                new CompletableFuture<>();

        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(incomplete);

        assertThatThrownBy(() -> adapter.sendRerouteSignal(signal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(callId.toString());
    }
}
