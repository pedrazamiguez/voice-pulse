package com.voicepulse.conversation.infrastructure.kafka.producer;

import com.voicepulse.conversation.domain.ports.outbound.TicketIntentPublisherPort;
import com.voicepulse.conversation.infrastructure.kafka.config.KafkaTopicProperties;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Secondary outbound infrastructure adapter implementing {@link TicketIntentPublisherPort}.
 *
 * <p>Publishes {@link TicketFormFilledEvent} envelopes to {@code telephony.tickets.intent} strictly
 * partitioned by {@code event.callId().toString()} per RFC-0001 §4.2 and AGENTS.md §3.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaTicketIntentPublisher implements TicketIntentPublisherPort {

    private static final String DEFAULT_TICKETS_INTENT_TOPIC = "telephony.tickets.intent";

    private final @NonNull KafkaTemplate<String, TicketFormFilledEvent> kafkaTemplate;
    private final @NonNull KafkaTopicProperties topicProperties;

    @Override
    public void publishTicketFilled(TicketFormFilledEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(event.callId(), "event callId must not be null");

        String topic =
                (topicProperties.ticketsIntent() != null
                                && topicProperties.ticketsIntent().name() != null
                                && !topicProperties.ticketsIntent().name().isBlank())
                        ? topicProperties.ticketsIntent().name()
                        : DEFAULT_TICKETS_INTENT_TOPIC;
        String messageKey = event.callId().toString();
        ProducerRecord<String, TicketFormFilledEvent> record =
                new ProducerRecord<>(topic, messageKey, event);

        CompletableFuture<SendResult<String, TicketFormFilledEvent>> future =
                kafkaTemplate.send(record);

        future.whenComplete(
                (result, ex) -> {
                    if (ex != null) {
                        log.error(
                                "Failed to publish TicketFormFilledEvent: topic={}, callId={},"
                                        + " ticketId={}, skillGroup={}",
                                topic,
                                messageKey,
                                event.ticketId(),
                                event.targetSkillGroup(),
                                ex);
                    } else {
                        log.info(
                                "TicketFormFilledEvent published successfully: topic={}, callId={},"
                                        + " ticketId={}, skillGroup={}, partition={}, offset={}",
                                topic,
                                messageKey,
                                event.ticketId(),
                                event.targetSkillGroup(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
