package com.voicepulse.routing.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoutingQueueQueryService Pure Domain Tests")
class RoutingQueueQueryServiceTest {

    private static final Instant T1 = Instant.parse("2026-11-15T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-15T08:01:00Z");
    private static final Instant T3 = Instant.parse("2026-11-15T08:02:00Z");

    @Mock private PriorityCallQueuePort priorityCallQueuePort;
    @Mock private TicketPersistencePort ticketPersistencePort;

    @InjectMocks private RoutingQueueQueryService queryService;

    private static TicketFormFilledEvent sampleTicket(SkillGroup skillGroup) {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(field(TicketFormFilledEvent::targetSkillGroup), skillGroup.name())
                .set(field(TicketFormFilledEvent::payload), Map.of())
                .create();
    }

    @Test
    @DisplayName("Rejects null constructor dependencies and null method inputs")
    void rejectsNullArguments() {
        assertThatThrownBy(() -> new RoutingQueueQueryService(null, ticketPersistencePort))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RoutingQueueQueryService(priorityCallQueuePort, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> queryService.listQueuedBySkillGroup(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> queryService.queueDepth(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> queryService.findTicketByCallId(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Lists queued tickets for a single skill group as an immutable copy")
    void listsQueuedBySkillGroup() {
        QueuedTicketItem item =
                new QueuedTicketItem(
                        sampleTicket(SkillGroup.DISRUPTION_DESK_GOLD),
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        100,
                        T1);
        when(priorityCallQueuePort.listQueuedBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .thenReturn(List.of(item));

        List<QueuedTicketItem> result =
                queryService.listQueuedBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD);

        assertThat(result).containsExactly(item);
        assertThatThrownBy(() -> result.add(item))
                .isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(ticketPersistencePort);
    }

    @Test
    @DisplayName("Aggregates all skill queues sorted by descending priority then FIFO enqueue time")
    void listsAllQueuedSortedByPriorityThenFifo() {
        QueuedTicketItem standardEarly =
                new QueuedTicketItem(
                        sampleTicket(SkillGroup.DISRUPTION_DESK_STANDARD),
                        SkillGroup.DISRUPTION_DESK_STANDARD,
                        50,
                        T1);
        QueuedTicketItem goldLate =
                new QueuedTicketItem(
                        sampleTicket(SkillGroup.DISRUPTION_DESK_GOLD),
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        100,
                        T3);
        QueuedTicketItem goldEarly =
                new QueuedTicketItem(
                        sampleTicket(SkillGroup.DISRUPTION_DESK_GOLD),
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        100,
                        T2);
        QueuedTicketItem accessibility =
                new QueuedTicketItem(
                        sampleTicket(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY),
                        SkillGroup.DISRUPTION_DESK_ACCESSIBILITY,
                        90,
                        T1);

        when(priorityCallQueuePort.listQueuedBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .thenReturn(List.of(goldLate, goldEarly));
        when(priorityCallQueuePort.listQueuedBySkillGroup(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY))
                .thenReturn(List.of(accessibility));
        when(priorityCallQueuePort.listQueuedBySkillGroup(SkillGroup.DISRUPTION_DESK_PRIORITY))
                .thenReturn(List.of());
        when(priorityCallQueuePort.listQueuedBySkillGroup(SkillGroup.DISRUPTION_DESK_STANDARD))
                .thenReturn(List.of(standardEarly));

        List<QueuedTicketItem> all = queryService.listAllQueued();

        assertThat(all).containsExactly(goldEarly, goldLate, accessibility, standardEarly);
    }

    @Test
    @DisplayName("Queries depths for every skill group and clamps negative values to zero")
    void returnsQueueDepthsBySkillGroup() {
        when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_GOLD)).thenReturn(3L);
        when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY))
                .thenReturn(1L);
        when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY)).thenReturn(0L);
        when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_STANDARD)).thenReturn(-2L);

        Map<SkillGroup, Long> depths = queryService.queueDepthsBySkillGroup();

        assertThat(depths)
                .containsExactly(
                        Map.entry(SkillGroup.DISRUPTION_DESK_GOLD, 3L),
                        Map.entry(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY, 1L),
                        Map.entry(SkillGroup.DISRUPTION_DESK_PRIORITY, 0L),
                        Map.entry(SkillGroup.DISRUPTION_DESK_STANDARD, 0L));
        assertThat(queryService.queueDepth(SkillGroup.DISRUPTION_DESK_GOLD)).isEqualTo(3L);
        assertThat(queryService.queueDepth(SkillGroup.DISRUPTION_DESK_STANDARD)).isZero();
    }

    @Test
    @DisplayName("Delegates ticket lookup by CallId to TicketPersistencePort")
    void findsTicketByCallId() {
        TicketFormFilledEvent event = sampleTicket(SkillGroup.DISRUPTION_DESK_PRIORITY);
        CallId callId = event.callId();
        when(ticketPersistencePort.findTicketByCallId(callId)).thenReturn(Optional.of(event));

        Optional<TicketFormFilledEvent> found = queryService.findTicketByCallId(callId);

        assertThat(found).contains(event);
        verify(ticketPersistencePort).findTicketByCallId(callId);
        verifyNoInteractions(priorityCallQueuePort);
    }
}
