package com.voicepulse.conversation.infrastructure.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.conversation.infrastructure.kafka.config.KafkaTopicProperties;
import com.voicepulse.conversation.infrastructure.kafka.config.KafkaTopicProperties.TopicConfig;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.model.CallId;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
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
@DisplayName("KafkaCallControlDispatcher Unit Tests")
class KafkaCallControlDispatcherTest {

    private static final String CONTROL_TOPIC = "telephony.calls.control";

    @Mock private KafkaTemplate<String, CallControlEnvelope> kafkaTemplate;

    @Captor private ArgumentCaptor<String> topicCaptor;

    @Captor private ArgumentCaptor<String> keyCaptor;

    @Captor private ArgumentCaptor<CallControlEnvelope> envelopeCaptor;

    private KafkaTopicProperties topicProperties;
    private KafkaCallControlDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        TopicConfig raw =
                new TopicConfig("telephony.calls.raw", 12, (short) 1, Duration.ofHours(1), null);
        TopicConfig control =
                new TopicConfig(CONTROL_TOPIC, 12, (short) 1, Duration.ofHours(1), null);
        TopicConfig dlq =
                new TopicConfig("telephony.calls.dlq", 3, (short) 1, Duration.ofHours(336), null);
        topicProperties = new KafkaTopicProperties(raw, control, dlq);

        dispatcher = new KafkaCallControlDispatcher(kafkaTemplate, topicProperties);
    }

    @Test
    @DisplayName(
            "dispatch publishes BargeInInterrupt using callId.toString() as Kafka partition key")
    void dispatchPublishesWithCallIdKey() {
        CallId callId = CallId.generate();
        Instant now = Instant.now();
        BargeInInterrupt interrupt = new BargeInInterrupt(callId, 42L, now, 120L);

        RecordMetadata metadata =
                new RecordMetadata(
                        new TopicPartition(CONTROL_TOPIC, 3), 0L, 0, now.toEpochMilli(), 0, 0);
        SendResult<String, CallControlEnvelope> sendResult = new SendResult<>(null, metadata);
        CompletableFuture<SendResult<String, CallControlEnvelope>> future =
                CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(eq(CONTROL_TOPIC), eq(callId.toString()), eq(interrupt)))
                .thenReturn(future);

        dispatcher.dispatch(interrupt);

        verify(kafkaTemplate)
                .send(topicCaptor.capture(), keyCaptor.capture(), envelopeCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo(CONTROL_TOPIC);
        assertThat(keyCaptor.getValue()).isEqualTo(callId.toString());
        assertThat(envelopeCaptor.getValue()).isEqualTo(interrupt);
    }

    @Test
    @DisplayName("dispatch handles asynchronous Kafka error gracefully")
    void dispatchHandlesAsyncError() {
        CallId callId = CallId.generate();
        BargeInInterrupt interrupt = new BargeInInterrupt(callId, 1L, Instant.now());

        CompletableFuture<SendResult<String, CallControlEnvelope>> failedFuture =
                CompletableFuture.failedFuture(new RuntimeException("Broker connection error"));

        when(kafkaTemplate.send(eq(CONTROL_TOPIC), eq(callId.toString()), eq(interrupt)))
                .thenReturn(failedFuture);

        dispatcher.dispatch(interrupt);

        verify(kafkaTemplate).send(CONTROL_TOPIC, callId.toString(), interrupt);
    }

    @Test
    @DisplayName("dispatch rejects null envelope")
    void dispatchRejectsNullEnvelope() {
        assertThatThrownBy(() -> dispatcher.dispatch(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("envelope cannot be null");
    }
}
