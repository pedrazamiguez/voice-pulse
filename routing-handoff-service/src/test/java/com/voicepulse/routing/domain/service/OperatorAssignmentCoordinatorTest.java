package com.voicepulse.routing.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.model.SkillMatchPlan;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("OperatorAssignmentCoordinator Domain Unit Tests")
class OperatorAssignmentCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-11-15T00:30:00Z");

    @Mock private OperatorPresencePort operatorPresencePort;
    @Mock private PriorityCallQueuePort priorityCallQueuePort;
    @Mock private HandoffPersistenceCoordinator persistenceCoordinator;
    @Mock private OperatorRerouteDispatcher dispatcher;

    @Captor private ArgumentCaptor<QueuedTicketItem> queueItemCaptor;
    @Captor private ArgumentCaptor<SkillMatchPlan> planCaptor;

    private OperatorAssignmentCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator =
                new OperatorAssignmentCoordinator(
                        new SkillGroupMatchingPolicy(),
                        operatorPresencePort,
                        priorityCallQueuePort,
                        persistenceCoordinator,
                        dispatcher,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TicketFormFilledEvent sampleTicket(
            String targetSkillGroup, boolean hotelRequired, boolean specialAssistance) {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(field(TicketFormFilledEvent::targetSkillGroup), targetSkillGroup)
                .set(field(TicketFormFilledEvent::hotelRequired), hotelRequired)
                .set(field(TicketFormFilledEvent::specialAssistance), specialAssistance)
                .set(
                        field(TicketFormFilledEvent::disruptionReason),
                        DisruptionReason.DELAYED_MAINTENANCE)
                .set(field(TicketFormFilledEvent::payload), Map.of())
                .create();
    }

    private static OperatorPresence availableOperator(String id, SkillGroup... skills) {
        return new OperatorPresence(
                OperatorId.fromString(id),
                "Operator " + id,
                OperatorAvailabilityStatus.AVAILABLE,
                Set.of(skills),
                Optional.empty(),
                NOW.minusSeconds(5));
    }

    @Test
    @DisplayName("Should claim primary available operator, persist, and dispatch reroute")
    void shouldAssignImmediateAvailableOperator() {
        TicketFormFilledEvent event = sampleTicket("DISRUPTION_DESK_GOLD", false, false);
        OperatorPresence operator = availableOperator("op-gold-1", SkillGroup.DISRUPTION_DESK_GOLD);
        HandoffAssignment assignment =
                new HandoffAssignment(
                        event.callId(),
                        event.ticketId(),
                        operator.operatorId(),
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        "/ws/operators/op-gold-1/calls/" + event.callId() + "/audio",
                        NOW);

        when(operatorPresencePort.findAvailableBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .thenReturn(List.of(operator));
        when(operatorPresencePort.tryClaimOperator(operator.operatorId(), event.callId(), NOW))
                .thenReturn(true);
        when(dispatcher.createAssignment(
                        event.callId(),
                        event.ticketId(),
                        operator.operatorId(),
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        NOW))
                .thenReturn(assignment);

        HandoffLifecycleState result = coordinator.processTicketIntent(event);

        HandoffLifecycleState.Assigned expectedState =
                new HandoffLifecycleState.Assigned(
                        event.callId(),
                        event.ticketId(),
                        operator.operatorId(),
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        NOW);
        assertThat(result).isEqualTo(expectedState);

        InOrder order = inOrder(operatorPresencePort, persistenceCoordinator, dispatcher);
        order.verify(operatorPresencePort)
                .tryClaimOperator(operator.operatorId(), event.callId(), NOW);
        order.verify(persistenceCoordinator)
                .saveImmediateAssignment(
                        eq(event),
                        any(SkillMatchPlan.class),
                        eq(assignment),
                        eq(expectedState),
                        eq(NOW));
        order.verify(dispatcher).dispatchAssignment(assignment, event, expectedState, NOW);
    }

    @Test
    @DisplayName("Should fall back to next skill tier when primary operator claim race is lost")
    void shouldFallbackWhenPrimaryClaimContended() {
        TicketFormFilledEvent event = sampleTicket("DISRUPTION_DESK_GOLD", false, false);
        OperatorPresence contendedGold =
                availableOperator("op-gold-race", SkillGroup.DISRUPTION_DESK_GOLD);
        OperatorPresence backupPriority =
                availableOperator("op-prio-2", SkillGroup.DISRUPTION_DESK_PRIORITY);
        HandoffAssignment assignment =
                new HandoffAssignment(
                        event.callId(),
                        event.ticketId(),
                        backupPriority.operatorId(),
                        SkillGroup.DISRUPTION_DESK_PRIORITY,
                        "/ws/operators/op-prio-2/calls/" + event.callId() + "/audio",
                        NOW);

        when(operatorPresencePort.findAvailableBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .thenReturn(List.of(contendedGold));
        when(operatorPresencePort.tryClaimOperator(contendedGold.operatorId(), event.callId(), NOW))
                .thenReturn(false);
        when(operatorPresencePort.findAvailableBySkillGroup(SkillGroup.DISRUPTION_DESK_PRIORITY))
                .thenReturn(List.of(backupPriority));
        when(operatorPresencePort.tryClaimOperator(
                        backupPriority.operatorId(), event.callId(), NOW))
                .thenReturn(true);
        when(dispatcher.createAssignment(
                        event.callId(),
                        event.ticketId(),
                        backupPriority.operatorId(),
                        SkillGroup.DISRUPTION_DESK_PRIORITY,
                        NOW))
                .thenReturn(assignment);

        HandoffLifecycleState result = coordinator.processTicketIntent(event);

        assertThat(result).isInstanceOf(HandoffLifecycleState.Assigned.class);
        verify(priorityCallQueuePort, never()).enqueue(any());
    }

    @Test
    @DisplayName("Should enqueue ticket, persist queued state, and broadcast metrics when busy")
    void shouldEnqueueAndBroadcastMetricsWhenNoOperatorAvailable() {
        TicketFormFilledEvent event = sampleTicket("DISRUPTION_DESK_STANDARD", true, false);

        when(operatorPresencePort.findAvailableBySkillGroup(any())).thenReturn(List.of());
        when(priorityCallQueuePort.enqueue(any())).thenReturn(2);
        when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY)).thenReturn(3L);

        HandoffLifecycleState result = coordinator.processTicketIntent(event);

        HandoffLifecycleState.Queued expectedQueued =
                new HandoffLifecycleState.Queued(
                        event.callId(),
                        event.ticketId(),
                        SkillGroup.DISRUPTION_DESK_PRIORITY,
                        2,
                        NOW);
        assertThat(result).isEqualTo(expectedQueued);

        verify(priorityCallQueuePort).enqueue(queueItemCaptor.capture());
        assertThat(queueItemCaptor.getValue().skillGroup())
                .isEqualTo(SkillGroup.DISRUPTION_DESK_PRIORITY);
        verify(persistenceCoordinator)
                .saveQueuedTicket(
                        eq(event), planCaptor.capture(), eq(expectedQueued), eq(2), eq(NOW));
        assertThat(planCaptor.getValue().primarySkillGroup())
                .isEqualTo(SkillGroup.DISRUPTION_DESK_PRIORITY);
        verify(dispatcher).broadcastQueueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY, 3L);
        verify(dispatcher).notifyLifecycleState(expectedQueued);
    }
}
