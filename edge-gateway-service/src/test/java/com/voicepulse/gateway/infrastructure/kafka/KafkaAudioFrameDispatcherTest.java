package com.voicepulse.gateway.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.gateway.infrastructure.config.GatewayKafkaProperties;
import com.voicepulse.shared.event.AudioChunkEnvelope;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
class KafkaAudioFrameDispatcherTest {

    private static final String TOPIC = "telephony.calls.raw";
    private static final Duration TIMEOUT = Duration.ofMillis(200);

    @Mock private KafkaTemplate<String, AudioChunkEnvelope> kafkaTemplate;

    @Captor private ArgumentCaptor<ProducerRecord<String, AudioChunkEnvelope>> recordCaptor;

    private KafkaPublisherMetricsTracker metricsTracker;
    private GatewayKafkaProperties properties;
    private KafkaAudioFrameDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        metricsTracker = new KafkaPublisherMetricsTracker();
        properties = new GatewayKafkaProperties(TOPIC, TIMEOUT, 33554432L, Duration.ofSeconds(3));
        dispatcher = new KafkaAudioFrameDispatcher(kafkaTemplate, properties, metricsTracker);
    }

    @Test
    @DisplayName("Partition Key Mandate: record key strictly matches callId.toString()")
    void dispatch_strictlyEnforcesPartitionKeyMandate() {
        CallId callId = CallId.generate();
        AudioChunkEnvelope envelope = createEnvelope(callId, 0L);
        CompletableFuture<SendResult<String, AudioChunkEnvelope>> future =
                new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        dispatcher.dispatch(callId, envelope);

        verify(kafkaTemplate).send(recordCaptor.capture());
        ProducerRecord<String, AudioChunkEnvelope> record = recordCaptor.getValue();

        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo(callId.toString());
        assertThat(record.value()).isEqualTo(envelope);
    }

    @Test
    @DisplayName("Asynchronous delivery success updates metrics tracker without blocking")
    void dispatch_whenFutureCompletesSuccessfully_updatesTrackerSuccess() {
        CallId callId = CallId.generate();
        AudioChunkEnvelope envelope = createEnvelope(callId, 1L);
        CompletableFuture<SendResult<String, AudioChunkEnvelope>> future =
                new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        dispatcher.dispatch(callId, envelope);

        assertThat(dispatcher.getMetrics().totalPublishedFrames()).isEqualTo(1L);
        assertThat(dispatcher.getMetrics().successfulPublishedFrames()).isZero();

        RecordMetadata metadata =
                new RecordMetadata(
                        new TopicPartition(TOPIC, 0), 0L, 0, System.currentTimeMillis(), 0, 0);
        ProducerRecord<String, AudioChunkEnvelope> record =
                new ProducerRecord<>(TOPIC, callId.toString(), envelope);
        future.complete(new SendResult<>(record, metadata));

        assertThat(dispatcher.getMetrics().successfulPublishedFrames()).isEqualTo(1L);
        assertThat(dispatcher.getMetrics().failedPublishedFrames()).isZero();
    }

    @Test
    @DisplayName("Asynchronous broker error logs structured failure and increments failure count")
    void dispatch_whenFutureCompletesExceptionally_updatesTrackerFailure() {
        CallId callId = CallId.generate();
        AudioChunkEnvelope envelope = createEnvelope(callId, 2L);
        CompletableFuture<SendResult<String, AudioChunkEnvelope>> future =
                new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        dispatcher.dispatch(callId, envelope);

        future.completeExceptionally(new RuntimeException("Kafka broker cluster unavailable"));

        assertThat(dispatcher.getMetrics().totalPublishedFrames()).isEqualTo(1L);
        assertThat(dispatcher.getMetrics().successfulPublishedFrames()).isZero();
        assertThat(dispatcher.getMetrics().failedPublishedFrames()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Send timeout expiration triggers failure in metrics tracker without throwing")
    void dispatch_whenTimeoutExpires_marksFailureInTracker() {
        CallId callId = CallId.generate();
        AudioChunkEnvelope envelope = createEnvelope(callId, 3L);
        CompletableFuture<SendResult<String, AudioChunkEnvelope>> unresolvedFuture =
                new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(unresolvedFuture);

        dispatcher.dispatch(callId, envelope);

        await().atMost(1, TimeUnit.SECONDS)
                .untilAsserted(
                        () ->
                                assertThat(dispatcher.getMetrics().failedPublishedFrames())
                                        .isEqualTo(1L));

        assertThat(dispatcher.getMetrics().totalPublishedFrames()).isEqualTo(1L);
        assertThat(dispatcher.getMetrics().successfulPublishedFrames()).isZero();
    }

    @Test
    @DisplayName("Synchronous send failure is caught, logged, and tracked without propagating")
    void dispatch_whenSendThrowsSynchronously_catchesAndRecordsFailure() {
        CallId callId = CallId.generate();
        AudioChunkEnvelope envelope = createEnvelope(callId, 4L);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new IllegalStateException("Producer closed"));

        dispatcher.dispatch(callId, envelope);

        assertThat(dispatcher.getMetrics().totalPublishedFrames()).isEqualTo(1L);
        assertThat(dispatcher.getMetrics().failedPublishedFrames()).isEqualTo(1L);
        assertThat(dispatcher.getMetrics().successfulPublishedFrames()).isZero();
    }

    @Test
    @DisplayName("Null arguments throw NullPointerException")
    void dispatch_withNullArguments_throwsNullPointerException() {
        CallId callId = CallId.generate();
        AudioChunkEnvelope envelope = createEnvelope(callId, 0L);

        assertThatThrownBy(() -> dispatcher.dispatch(null, envelope))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("callId cannot be null");

        assertThatThrownBy(() -> dispatcher.dispatch(callId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("envelope cannot be null");
    }

    private AudioChunkEnvelope createEnvelope(CallId callId, long sequenceNumber) {
        return new AudioChunkEnvelope(
                callId, sequenceNumber, Instant.now(), AudioFormat.PCM_16KHZ_MONO, new byte[640]);
    }
}
