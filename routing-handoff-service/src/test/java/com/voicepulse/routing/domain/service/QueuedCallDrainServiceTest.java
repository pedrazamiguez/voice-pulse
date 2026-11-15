package com.voicepulse.routing.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueuedCallDrainService Domain Unit Tests")
class QueuedCallDrainServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T00:10:00Z");

    @Mock private OperatorPresencePort operatorPresencePort;
    @Mock private PriorityCallQueuePort priorityCallQueuePort;
    @Mock private HandoffPersistenceCoordinator persistenceCoordinator;
    @Mock private OperatorRerouteDispatcher dispatcher;

    private QueuedCallDrainService drainService;

    @BeforeEach
    void setUp() {
        drainService =
                new QueuedCallDrainService(
                        operatorPresencePort,
                        priorityCallQueuePort,
                        persistenceCoordinator,
                        dispatcher);
    }

    private static TicketFormFilledEvent sampleEvent(String skillGroup) {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(field(TicketFormFilledEvent::targetSkillGroup), skillGroup)
                .set(field(TicketFormFilledEvent::payload), Map.of())
                .create();
    }

    @Test
    @DisplayName("Should drain skills in descending defaultPriorityScore order and claim ticket")
    void shouldDrainInDescendingSkillPriorityOrder() {
        OperatorId operatorId = OperatorId.fromString("op-multi-1");
        TicketFormFilledEvent goldEvent = sampleEvent("DISRUPTION_DESK_GOLD");
        QueuedTicketItem goldQueued =
                new QueuedTicketItem(goldEvent, SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW);
        HandoffAssignment expectedAssignment =
                new HandoffAssignment(
                        goldEvent.callId(),
                        goldEvent.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        "/ws/operators/op-multi-1/calls/" + goldEvent.callId() + "/audio",
                        NOW);

        when(priorityCallQueuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD))
                .thenReturn(Optional.of(goldQueued));
        when(operatorPresencePort.tryClaimOperator(operatorId, goldEvent.callId(), NOW))
                .thenReturn(true);
        when(dispatcher.createAssignment(
                        goldEvent.callId(),
                        goldEvent.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        NOW))
                .thenReturn(expectedAssignment);
        when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_GOLD)).thenReturn(2L);

        Optional<HandoffAssignment> drained =
                drainService.drainNextForSkills(
                        operatorId,
                        Set.of(
                                SkillGroup.DISRUPTION_DESK_STANDARD,
                                SkillGroup.DISRUPTION_DESK_GOLD),
                        NOW);

        assertThat(drained).contains(expectedAssignment);
        InOrder order =
                inOrder(
                        priorityCallQueuePort,
                        operatorPresencePort,
                        persistenceCoordinator,
                        dispatcher);
        order.verify(priorityCallQueuePort).dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD);
        order.verify(operatorPresencePort).tryClaimOperator(operatorId, goldEvent.callId(), NOW);
        order.verify(persistenceCoordinator).recordQueuedClaim(goldQueued, expectedAssignment, NOW);
        order.verify(dispatcher).broadcastQueueDepth(SkillGroup.DISRUPTION_DESK_GOLD, 2L);
        order.verify(dispatcher)
                .dispatchAssignment(
                        eq(expectedAssignment),
                        eq(goldEvent),
                        any(HandoffLifecycleState.Assigned.class),
                        eq(NOW));
    }

    @Test
    @DisplayName("Should re-enqueue dequeued ticket and broadcast depth if claim race is lost")
    void shouldReEnqueueWhenClaimRaceLost() {
        OperatorId operatorId = OperatorId.fromString("op-race-1");
        TicketFormFilledEvent event = sampleEvent("DISRUPTION_DESK_PRIORITY");
        QueuedTicketItem queuedItem =
                new QueuedTicketItem(event, SkillGroup.DISRUPTION_DESK_PRIORITY, 80, NOW);

        when(operatorPresencePort.tryClaimOperator(operatorId, event.callId(), NOW))
                .thenReturn(false);
        when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY)).thenReturn(1L);

        Optional<HandoffAssignment> claimed =
                drainService.claimQueuedItem(operatorId, queuedItem, NOW);

        assertThat(claimed).isEmpty();
        verify(priorityCallQueuePort).enqueue(queuedItem);
        verify(dispatcher).broadcastQueueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY, 1L);
        verifyNoInteractions(persistenceCoordinator);
    }

    @Test
    @DisplayName("Should return operator presence only when AVAILABLE")
    void shouldFilterAvailableOperatorOnly() {
        OperatorId availableId = OperatorId.fromString("op-avail");
        OperatorPresence availablePresence =
                new OperatorPresence(
                        availableId,
                        "Available",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_STANDARD),
                        Optional.empty(),
                        NOW);
        OperatorId pausedId = OperatorId.fromString("op-paused");
        OperatorPresence pausedPresence =
                new OperatorPresence(
                        pausedId,
                        "Paused",
                        OperatorAvailabilityStatus.PAUSED,
                        Set.of(SkillGroup.DISRUPTION_DESK_STANDARD),
                        Optional.empty(),
                        NOW);

        when(operatorPresencePort.findPresence(availableId))
                .thenReturn(Optional.of(availablePresence));
        when(operatorPresencePort.findPresence(pausedId)).thenReturn(Optional.of(pausedPresence));

        assertThat(drainService.findAvailableOperator(availableId)).contains(availablePresence);
        assertThat(drainService.findAvailableOperator(pausedId)).isEmpty();
    }
}
