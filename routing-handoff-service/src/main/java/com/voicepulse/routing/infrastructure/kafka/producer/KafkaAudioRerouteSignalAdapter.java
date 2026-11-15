package com.voicepulse.routing.infrastructure.kafka.producer;

import com.voicepulse.routing.domain.ports.outbound.AudioRerouteSignalPort;
import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import com.voicepulse.routing.infrastructure.kafka.config.RoutingKafkaProperties;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Secondary outbound Kafka adapter implementing {@link AudioRerouteSignalPort}.
 *
 * <p>Publishes {@link OperatorRerouteSignal} envelopes to {@code telephony.calls.control} strictly
 * keyed by {@code signal.callId().toString()} per AGENTS.md §3.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "voicepulse.routing.kafka",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class KafkaAudioRerouteSignalAdapter implements AudioRerouteSignalPort {

    private final @NonNull KafkaTemplate<String, CallControlEnvelope>
            routingCallControlKafkaTemplate;
    private final @NonNull RoutingProperties routingProperties;
    private final @NonNull RoutingKafkaProperties routingKafkaProperties;

    @Override
    public void sendRerouteSignal(OperatorRerouteSignal signal) {
        Objects.requireNonNull(signal, "signal cannot be null");
        Objects.requireNonNull(signal.callId(), "signal callId cannot be null");

        String topic = routingProperties.callControlTopic();
        String messageKey = signal.callId().toString();
        ProducerRecord<String, CallControlEnvelope> record =
                new ProducerRecord<>(topic, messageKey, signal);

        CompletableFuture<SendResult<String, CallControlEnvelope>> future =
                routingCallControlKafkaTemplate.send(record);

        try {
            SendResult<String, CallControlEnvelope> result =
                    future.get(
                            routingKafkaProperties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            int partition =
                    (result != null && result.getRecordMetadata() != null)
                            ? result.getRecordMetadata().partition()
                            : -1;
            long offset =
                    (result != null && result.getRecordMetadata() != null)
                            ? result.getRecordMetadata().offset()
                            : -1L;
            log.info(
                    "OperatorRerouteSignal published: topic={}, callId={}, operatorId={},"
                            + " bridgeEndpoint={}, partition={}, offset={}",
                    topic,
                    messageKey,
                    signal.operatorId(),
                    signal.bridgeEndpoint(),
                    partition,
                    offset);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while publishing OperatorRerouteSignal for callId=" + messageKey,
                    ex);
        } catch (ExecutionException | TimeoutException ex) {
            log.error(
                    "Failed to publish OperatorRerouteSignal: topic={}, callId={}, operatorId={}",
                    topic,
                    messageKey,
                    signal.operatorId(),
                    ex);
            throw new IllegalStateException(
                    "Failed to publish OperatorRerouteSignal for callId=" + messageKey, ex);
        }
    }
}
