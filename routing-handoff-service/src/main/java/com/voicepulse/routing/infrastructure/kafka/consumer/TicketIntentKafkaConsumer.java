package com.voicepulse.routing.infrastructure.kafka.consumer;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.ports.inbound.ProcessTicketIntentUseCase;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Primary inbound Kafka adapter consuming finalized {@link TicketFormFilledEvent} envelopes from
 * {@code telephony.tickets.intent} and delegating routing decisions to {@link
 * ProcessTicketIntentUseCase}.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "voicepulse.routing.kafka",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class TicketIntentKafkaConsumer {

    private final @NonNull ProcessTicketIntentUseCase processTicketIntentUseCase;

    @KafkaListener(
            id = "routingTicketIntentListener",
            topics = "${voicepulse.routing.ticket-intent-topic}",
            groupId = "${voicepulse.routing.consumer-group}",
            containerFactory = "routingTicketIntentKafkaListenerContainerFactory")
    public void onTicketIntent(ConsumerRecord<String, TicketFormFilledEvent> record) {
        if (record == null || record.value() == null) {
            log.warn("Ignoring null TicketFormFilledEvent consumer record");
            return;
        }

        TicketFormFilledEvent event = record.value();
        String expectedKey = event.callId().toString();
        if (!expectedKey.equals(record.key())) {
            log.warn(
                    "TicketFormFilledEvent Kafka key mismatch: expectedCallId={}, actualKey={},"
                            + " partition={}, offset={}",
                    expectedKey,
                    record.key(),
                    record.partition(),
                    record.offset());
        }

        log.info(
                "Consumed TicketFormFilledEvent: callId={}, ticketId={}, targetSkillGroup={},"
                        + " partition={}, offset={}",
                expectedKey,
                event.ticketId(),
                event.targetSkillGroup(),
                record.partition(),
                record.offset());

        HandoffLifecycleState resultingState =
                processTicketIntentUseCase.processTicketIntent(event);
        log.info(
                "Processed TicketFormFilledEvent: callId={}, ticketId={}, resultingState={}",
                expectedKey,
                event.ticketId(),
                resultingState.getClass().getSimpleName());
    }
}
