package com.voicepulse.gateway.infrastructure.kafka;

import com.voicepulse.gateway.infrastructure.config.GatewayKafkaProperties;
import com.voicepulse.shared.event.AudioChunkEnvelope;
import com.voicepulse.shared.model.CallId;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Reactive Kafka dispatcher handling asynchronous publishing of audio frame envelopes to the
 * telephony Kafka topic.
 *
 * <p>Enforces the Partition Key Mandate ({@link CallId#toString()}) for in-order per-call partition
 * affinity, applies non-blocking timeout guards, and updates telemetry metrics.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "voicepulse.gateway",
        name = "audio-publisher",
        havingValue = "kafka",
        matchIfMissing = true)
public class KafkaAudioFrameDispatcher {

    private final @NonNull KafkaTemplate<String, AudioChunkEnvelope> kafkaTemplate;
    private final @NonNull GatewayKafkaProperties properties;
    private final @NonNull KafkaPublisherMetricsTracker metricsTracker;

    /**
     * Dispatches an audio chunk envelope to Kafka asynchronously without blocking.
     *
     * @param callId call identifier used as partition key
     * @param envelope audio chunk envelope payload
     */
    public void dispatch(CallId callId, AudioChunkEnvelope envelope) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(envelope, "envelope cannot be null");

        String partitionKey = callId.toString();
        ProducerRecord<String, AudioChunkEnvelope> record =
                new ProducerRecord<>(properties.topic(), partitionKey, envelope);

        metricsTracker.recordAttempt();
        long startNanos = System.nanoTime();

        try {
            CompletableFuture<SendResult<String, AudioChunkEnvelope>> future =
                    kafkaTemplate.send(record);

            future.orTimeout(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete(
                            (result, ex) -> {
                                long latencyMicros =
                                        Math.max(0L, (System.nanoTime() - startNanos) / 1000L);
                                if (ex != null) {
                                    metricsTracker.recordFailure(latencyMicros);
                                    log.error(
                                            "Failed to publish audio chunk to Kafka: callId={},"
                                                    + " seq={}, topic={}, error={}",
                                            partitionKey,
                                            envelope.sequenceNumber(),
                                            properties.topic(),
                                            ex.getMessage(),
                                            ex);
                                } else {
                                    metricsTracker.recordSuccess(latencyMicros);
                                    if (result != null && result.getRecordMetadata() != null) {
                                        log.debug(
                                                "Audio chunk published: callId={}, seq={},"
                                                        + " topic={}, partition={}, offset={}",
                                                partitionKey,
                                                envelope.sequenceNumber(),
                                                properties.topic(),
                                                result.getRecordMetadata().partition(),
                                                result.getRecordMetadata().offset());
                                    }
                                }
                            });
        } catch (Exception ex) {
            long latencyMicros = Math.max(0L, (System.nanoTime() - startNanos) / 1000L);
            metricsTracker.recordFailure(latencyMicros);
            log.error(
                    "Synchronous failure dispatching audio chunk to Kafka: callId={}, seq={},"
                            + " topic={}, error={}",
                    partitionKey,
                    envelope.sequenceNumber(),
                    properties.topic(),
                    ex.getMessage(),
                    ex);
        }
    }

    /**
     * Retrieves current metrics snapshot from internal tracker.
     *
     * @return current metrics snapshot
     */
    public KafkaProducerMetrics getMetrics() {
        return metricsTracker.snapshot();
    }
}
