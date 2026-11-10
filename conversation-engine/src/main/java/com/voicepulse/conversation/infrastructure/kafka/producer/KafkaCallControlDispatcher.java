package com.voicepulse.conversation.infrastructure.kafka.producer;

import com.voicepulse.conversation.domain.ports.outbound.CallControlDispatcherPort;
import com.voicepulse.conversation.infrastructure.kafka.config.KafkaTopicProperties;
import com.voicepulse.shared.event.CallControlEnvelope;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Secondary outbound infrastructure adapter implementing {@link CallControlDispatcherPort}.
 *
 * <p>Publishes polymorphic {@link CallControlEnvelope} signaling events (barge-in interrupts, call
 * lifecycle changes) to the configured Kafka control topic. Enforces the Partition Key Mandate by
 * strictly using {@code envelope.callId().toString()} as the Kafka partition key for deterministic
 * in-order processing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaCallControlDispatcher implements CallControlDispatcherPort {

    private final @NonNull KafkaTemplate<String, CallControlEnvelope> kafkaTemplate;
    private final @NonNull KafkaTopicProperties topicProperties;

    @Override
    public void dispatch(CallControlEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope cannot be null");
        Objects.requireNonNull(envelope.callId(), "envelope callId cannot be null");

        String topic = topicProperties.control().name();
        String messageKey = envelope.callId().toString();

        CompletableFuture<SendResult<String, CallControlEnvelope>> future =
                kafkaTemplate.send(topic, messageKey, envelope);

        future.whenComplete(
                (result, ex) -> {
                    if (ex != null) {
                        log.error(
                                "Failed to dispatch CallControlEnvelope: topic={}, callId={},"
                                        + " seq={}, event={}",
                                topic,
                                messageKey,
                                envelope.sequenceNumber(),
                                envelope.getClass().getSimpleName(),
                                ex);
                    } else {
                        log.info(
                                "CallControlEnvelope dispatched successfully: topic={}, callId={},"
                                        + " partition={}, offset={}, event={}",
                                topic,
                                messageKey,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                envelope.getClass().getSimpleName());
                    }
                });
    }
}
