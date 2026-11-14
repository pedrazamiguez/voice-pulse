package com.voicepulse.routing.infrastructure.kafka.consumer;

import static org.instancio.Select.field;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.inbound.ProcessTicketIntentUseCase;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TicketIntentKafkaConsumer Unit Tests")
class TicketIntentKafkaConsumerTest {

    private static final Instant NOW = Instant.parse("2026-11-14T16:00:00Z");

    @Mock private ProcessTicketIntentUseCase processTicketIntentUseCase;

    @InjectMocks private TicketIntentKafkaConsumer consumer;

    private static TicketFormFilledEvent sampleEvent() {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(field(TicketFormFilledEvent::targetSkillGroup), "DISRUPTION_DESK_GOLD")
                .set(field(TicketFormFilledEvent::payload), Map.of())
                .create();
    }

    @Test
    @DisplayName("Should delegate valid TicketFormFilledEvent to ProcessTicketIntentUseCase")
    void shouldDelegateValidTicketIntent() {
        TicketFormFilledEvent event = sampleEvent();
        ConsumerRecord<String, TicketFormFilledEvent> record =
                new ConsumerRecord<>(
                        "telephony.tickets.intent", 0, 12L, event.callId().toString(), event);

        when(processTicketIntentUseCase.processTicketIntent(event))
                .thenReturn(
                        new HandoffLifecycleState.Queued(
                                event.callId(),
                                event.ticketId(),
                                SkillGroup.DISRUPTION_DESK_GOLD,
                                1,
                                NOW));

        consumer.onTicketIntent(record);

        verify(processTicketIntentUseCase).processTicketIntent(event);
    }

    @Test
    @DisplayName("Should still process event when Kafka key differs from event callId")
    void shouldProcessEventEvenOnKeyMismatch() {
        TicketFormFilledEvent event = sampleEvent();
        ConsumerRecord<String, TicketFormFilledEvent> record =
                new ConsumerRecord<>("telephony.tickets.intent", 2, 7L, "mismatched-key", event);

        when(processTicketIntentUseCase.processTicketIntent(event))
                .thenReturn(
                        new HandoffLifecycleState.Queued(
                                event.callId(),
                                event.ticketId(),
                                SkillGroup.DISRUPTION_DESK_GOLD,
                                1,
                                NOW));

        consumer.onTicketIntent(record);

        verify(processTicketIntentUseCase).processTicketIntent(event);
    }

    @Test
    @DisplayName("Should ignore null ConsumerRecord or null payload")
    void shouldIgnoreNullRecordAndNullValue() {
        ConsumerRecord<String, TicketFormFilledEvent> tombstoneRecord =
                new ConsumerRecord<>("telephony.tickets.intent", 0, 1L, "some-key", null);

        consumer.onTicketIntent(null);
        consumer.onTicketIntent(tombstoneRecord);

        verifyNoInteractions(processTicketIntentUseCase);
    }
}
