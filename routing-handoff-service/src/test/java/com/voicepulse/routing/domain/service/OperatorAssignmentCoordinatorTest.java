package com.voicepulse.routing.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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
import com.voicepulse.routing.domain.ports.outbound.AudioRerouteSignalPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorDispatchPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
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
import org.junit.jupiter.api.Nested;
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

    private static final Instant NOW = Instant.parse("2026-11-14T15:00:00Z");
    private static final String BRIDGE_BASE_PATH = "/ws/operators";

    @Mock private TicketPersistencePort ticketPersistencePort;
    @Mock private OperatorPresencePort operatorPresencePort;
    @Mock private PriorityCallQueuePort priorityCallQueuePort;
    @Mock private OperatorDispatchPort operatorDispatchPort;
    @Mock private AudioRerouteSignalPort audioRerouteSignalPort;

    @Captor private ArgumentCaptor<HandoffAssignment> assignmentCaptor;
    @Captor private ArgumentCaptor<HandoffLifecycleState> stateCaptor;
    @Captor private ArgumentCaptor<OperatorRerouteSignal> signalCaptor;
    @Captor private ArgumentCaptor<QueuedTicketItem> queueItemCaptor;
    @Captor private ArgumentCaptor<OperatorPresence> presenceCaptor;

    private OperatorAssignmentCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator =
                new OperatorAssignmentCoordinator(
                        new SkillGroupMatchingPolicy(),
                        ticketPersistencePort,
                        operatorPresencePort,
                        priorityCallQueuePort,
                        operatorDispatchPort,
                        audioRerouteSignalPort,
                        BRIDGE_BASE_PATH,
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

    @Nested
    @DisplayName("ProcessTicketIntentUseCase Immediate & Fallback Routing")
    class ProcessTicketIntentTests {

        @Test
        @DisplayName("Should claim available primary operator, persist, dispatch, and emit reroute")
        void shouldAssignImmediateAvailableOperatorAndEmitReroute() {
            TicketFormFilledEvent event = sampleTicket("DISRUPTION_DESK_GOLD", false, false);
            OperatorPresence op = availableOperator("op-gold-1", SkillGroup.DISRUPTION_DESK_GOLD);

            when(operatorPresencePort.findAvailableBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                    .thenReturn(List.of(op));
            when(operatorPresencePort.tryClaimOperator(op.operatorId(), event.callId(), NOW))
                    .thenReturn(true);

            HandoffLifecycleState result = coordinator.processTicketIntent(event);

            assertThat(result)
                    .isEqualTo(
                            new HandoffLifecycleState.Assigned(
                                    event.callId(),
                                    event.ticketId(),
                                    op.operatorId(),
                                    SkillGroup.DISRUPTION_DESK_GOLD,
                                    NOW));

            verify(ticketPersistencePort)
                    .saveTicket(event, SkillGroup.DISRUPTION_DESK_GOLD, result);
            verify(ticketPersistencePort)
                    .recordHandoffAuditLog(
                            eq(event.callId()),
                            eq("OPERATOR_ASSIGNED"),
                            isNull(),
                            eq("ASSIGNED"),
                            eq(op.operatorId()),
                            anyMap(),
                            eq(NOW));
            verify(operatorDispatchPort).pushAssignment(assignmentCaptor.capture(), eq(event));
            verify(operatorDispatchPort).notifyHandoffStateChanged(result);
            verify(audioRerouteSignalPort).sendRerouteSignal(signalCaptor.capture());

            HandoffAssignment assignment = assignmentCaptor.getValue();
            assertThat(assignment.bridgeEndpoint())
                    .isEqualTo("/ws/operators/op-gold-1/calls/" + event.callId() + "/audio");
            assertThat(signalCaptor.getValue().callId()).isEqualTo(event.callId());
            assertThat(signalCaptor.getValue().operatorId()).isEqualTo("op-gold-1");
            assertThat(signalCaptor.getValue().bridgeEndpoint())
                    .isEqualTo(assignment.bridgeEndpoint());
            verify(priorityCallQueuePort, never()).enqueue(any());
        }

        @Test
        @DisplayName("Should cascade to fallback skill desk when primary operators lose claim race")
        void shouldCascadeToFallbackSkillWhenClaimContended() {
            TicketFormFilledEvent event = sampleTicket("DISRUPTION_DESK_GOLD", false, false);
            OperatorPresence contendedGold =
                    availableOperator("op-gold-busy", SkillGroup.DISRUPTION_DESK_GOLD);
            OperatorPresence fallbackPriority =
                    availableOperator("op-prio-1", SkillGroup.DISRUPTION_DESK_PRIORITY);

            when(operatorPresencePort.findAvailableBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                    .thenReturn(List.of(contendedGold));
            when(operatorPresencePort.tryClaimOperator(
                            contendedGold.operatorId(), event.callId(), NOW))
                    .thenReturn(false);
            when(operatorPresencePort.findAvailableBySkillGroup(
                            SkillGroup.DISRUPTION_DESK_PRIORITY))
                    .thenReturn(List.of(fallbackPriority));
            when(operatorPresencePort.tryClaimOperator(
                            fallbackPriority.operatorId(), event.callId(), NOW))
                    .thenReturn(true);

            HandoffLifecycleState result = coordinator.processTicketIntent(event);

            assertThat(result).isInstanceOf(HandoffLifecycleState.Assigned.class);
            HandoffLifecycleState.Assigned assigned = (HandoffLifecycleState.Assigned) result;
            assertThat(assigned.operatorId()).isEqualTo(fallbackPriority.operatorId());
            assertThat(assigned.skillGroup()).isEqualTo(SkillGroup.DISRUPTION_DESK_PRIORITY);
            verify(ticketPersistencePort)
                    .saveTicket(event, SkillGroup.DISRUPTION_DESK_GOLD, assigned);
        }

        @Test
        @DisplayName("Should release claimed operator if ticket persistence fails")
        void shouldCompensateOperatorClaimWhenPersistenceFails() {
            TicketFormFilledEvent event = sampleTicket("DISRUPTION_DESK_STANDARD", false, false);
            OperatorPresence op =
                    availableOperator("op-std-1", SkillGroup.DISRUPTION_DESK_STANDARD);

            when(operatorPresencePort.findAvailableBySkillGroup(
                            SkillGroup.DISRUPTION_DESK_STANDARD))
                    .thenReturn(List.of(op));
            when(operatorPresencePort.tryClaimOperator(op.operatorId(), event.callId(), NOW))
                    .thenReturn(true);
            doThrow(new IllegalStateException("PostgreSQL unavailable"))
                    .when(ticketPersistencePort)
                    .saveTicket(any(), any(), any());

            assertThatThrownBy(() -> coordinator.processTicketIntent(event))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PostgreSQL unavailable");

            verify(operatorPresencePort)
                    .releaseOperator(op.operatorId(), OperatorAvailabilityStatus.AVAILABLE, NOW);
            verify(audioRerouteSignalPort, never()).sendRerouteSignal(any());
        }

        @Test
        @DisplayName("Should enqueue under primary skill group when no candidate operator is free")
        void shouldEnqueueCallWhenNoOperatorAvailable() {
            TicketFormFilledEvent event = sampleTicket("DISRUPTION_DESK_STANDARD", true, false);

            when(operatorPresencePort.findAvailableBySkillGroup(any())).thenReturn(List.of());
            when(priorityCallQueuePort.enqueue(any())).thenReturn(2);
            when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY))
                    .thenReturn(2L);

            HandoffLifecycleState result = coordinator.processTicketIntent(event);

            assertThat(result)
                    .isEqualTo(
                            new HandoffLifecycleState.Queued(
                                    event.callId(),
                                    event.ticketId(),
                                    SkillGroup.DISRUPTION_DESK_PRIORITY,
                                    2,
                                    NOW));

            verify(priorityCallQueuePort).enqueue(queueItemCaptor.capture());
            assertThat(queueItemCaptor.getValue().skillGroup())
                    .isEqualTo(SkillGroup.DISRUPTION_DESK_PRIORITY);
            assertThat(queueItemCaptor.getValue().priorityScore()).isEqualTo(100);
            verify(ticketPersistencePort)
                    .saveTicket(event, SkillGroup.DISRUPTION_DESK_PRIORITY, result);
            verify(operatorDispatchPort)
                    .broadcastQueueMetrics(SkillGroup.DISRUPTION_DESK_PRIORITY, 2L);
            verify(operatorDispatchPort).notifyHandoffStateChanged(result);
            verify(audioRerouteSignalPort, never()).sendRerouteSignal(any());
        }

        @Test
        @DisplayName("Should compensate by removing queued item if persistence fails after enqueue")
        void shouldRemoveQueuedItemWhenPersistenceFailsDuringEnqueue() {
            TicketFormFilledEvent event = sampleTicket("DISRUPTION_DESK_STANDARD", false, false);

            when(operatorPresencePort.findAvailableBySkillGroup(
                            SkillGroup.DISRUPTION_DESK_STANDARD))
                    .thenReturn(List.of());
            when(priorityCallQueuePort.enqueue(any())).thenReturn(1);
            doThrow(new IllegalStateException("DB failure"))
                    .when(ticketPersistencePort)
                    .saveTicket(any(), any(), any());

            assertThatThrownBy(() -> coordinator.processTicketIntent(event))
                    .isInstanceOf(IllegalStateException.class);

            verify(priorityCallQueuePort).removeByCallId(event.callId());
        }
    }

    @Nested
    @DisplayName("Queue-Drain, Presence Management & Claim Use Cases")
    class PresenceAndClaimTests {

        @Test
        @DisplayName("Should automatically drain highest-priority queued call on AVAILABLE status")
        void shouldDrainHighestPriorityCallWhenOperatorBecomesAvailable() {
            OperatorId operatorId = OperatorId.fromString("op-multi-1");
            TicketFormFilledEvent goldTicket = sampleTicket("DISRUPTION_DESK_GOLD", false, false);
            QueuedTicketItem queuedGold =
                    new QueuedTicketItem(
                            goldTicket, SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW.minusSeconds(30));

            when(operatorPresencePort.findPresence(operatorId)).thenReturn(Optional.empty());
            when(priorityCallQueuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD))
                    .thenReturn(Optional.of(queuedGold));
            when(operatorPresencePort.tryClaimOperator(operatorId, goldTicket.callId(), NOW))
                    .thenReturn(true);
            when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_GOLD)).thenReturn(0L);

            OperatorPresence resultingPresence =
                    coordinator.updatePresence(
                            operatorId,
                            "Multi Operator",
                            OperatorAvailabilityStatus.AVAILABLE,
                            Set.of(
                                    SkillGroup.DISRUPTION_DESK_STANDARD,
                                    SkillGroup.DISRUPTION_DESK_GOLD),
                            NOW);

            verify(operatorPresencePort).savePresence(presenceCaptor.capture());
            assertThat(presenceCaptor.getValue().status())
                    .isEqualTo(OperatorAvailabilityStatus.AVAILABLE);
            assertThat(resultingPresence.status()).isEqualTo(OperatorAvailabilityStatus.BUSY);
            assertThat(resultingPresence.activeCallId()).contains(goldTicket.callId());

            verify(ticketPersistencePort)
                    .updateTicketStatus(goldTicket.ticketId(), "ASSIGNED", NOW);
            verify(operatorDispatchPort).broadcastQueueMetrics(SkillGroup.DISRUPTION_DESK_GOLD, 0L);
            verify(operatorDispatchPort).pushAssignment(assignmentCaptor.capture(), eq(goldTicket));
            verify(audioRerouteSignalPort).sendRerouteSignal(signalCaptor.capture());
            assertThat(assignmentCaptor.getValue().operatorId()).isEqualTo(operatorId);
            assertThat(signalCaptor.getValue().callId()).isEqualTo(goldTicket.callId());
        }

        @Test
        @DisplayName("Should re-enqueue dequeued item if atomic operator claim loses race")
        void shouldReEnqueueItemWhenDrainLosesClaimRace() {
            OperatorId operatorId = OperatorId.fromString("op-race-1");
            TicketFormFilledEvent ticket = sampleTicket("DISRUPTION_DESK_GOLD", false, false);
            QueuedTicketItem queuedGold =
                    new QueuedTicketItem(
                            ticket, SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW.minusSeconds(20));

            when(operatorPresencePort.findPresence(operatorId)).thenReturn(Optional.empty());
            when(priorityCallQueuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD))
                    .thenReturn(Optional.of(queuedGold));
            when(operatorPresencePort.tryClaimOperator(operatorId, ticket.callId(), NOW))
                    .thenReturn(false);
            when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_GOLD)).thenReturn(1L);

            OperatorPresence presence =
                    coordinator.updatePresence(
                            operatorId,
                            "Race Operator",
                            OperatorAvailabilityStatus.AVAILABLE,
                            Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                            NOW);

            assertThat(presence.status()).isEqualTo(OperatorAvailabilityStatus.AVAILABLE);
            verify(priorityCallQueuePort).enqueue(queuedGold);
            verify(audioRerouteSignalPort, never()).sendRerouteSignal(any());
        }

        @Test
        @DisplayName("Should claim specific queued call for available operator")
        void shouldClaimSpecificQueuedCall() {
            OperatorPresence operator =
                    availableOperator("op-claim-1", SkillGroup.DISRUPTION_DESK_PRIORITY);
            TicketFormFilledEvent ticket = sampleTicket("DISRUPTION_DESK_PRIORITY", false, false);
            QueuedTicketItem item =
                    new QueuedTicketItem(
                            ticket, SkillGroup.DISRUPTION_DESK_PRIORITY, 80, NOW.minusSeconds(10));

            when(operatorPresencePort.findPresence(operator.operatorId()))
                    .thenReturn(Optional.of(operator));
            when(priorityCallQueuePort.removeByCallId(ticket.callId()))
                    .thenReturn(Optional.of(item));
            when(operatorPresencePort.tryClaimOperator(operator.operatorId(), ticket.callId(), NOW))
                    .thenReturn(true);
            when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY))
                    .thenReturn(0L);

            Optional<HandoffAssignment> assignment =
                    coordinator.claimSpecificCall(operator.operatorId(), ticket.callId(), NOW);

            assertThat(assignment).isPresent();
            assertThat(assignment.get().operatorId()).isEqualTo(operator.operatorId());
            assertThat(assignment.get().callId()).isEqualTo(ticket.callId());
            verify(audioRerouteSignalPort).sendRerouteSignal(any());
        }

        @Test
        @DisplayName("Should reject manual claim when operator is not AVAILABLE")
        void shouldRejectClaimWhenOperatorUnavailable() {
            OperatorId operatorId = OperatorId.fromString("op-paused-1");
            OperatorPresence paused =
                    new OperatorPresence(
                            operatorId,
                            "Paused Operator",
                            OperatorAvailabilityStatus.PAUSED,
                            Set.of(SkillGroup.DISRUPTION_DESK_STANDARD),
                            Optional.empty(),
                            NOW);
            CallId callId = CallId.generate();

            when(operatorPresencePort.findPresence(operatorId)).thenReturn(Optional.of(paused));

            assertThat(coordinator.claimNextQueuedCall(operatorId, NOW)).isEmpty();
            assertThat(coordinator.claimSpecificCall(operatorId, callId, NOW)).isEmpty();
            verify(priorityCallQueuePort, never()).dequeueHighestPriority(any());
            verify(priorityCallQueuePort, never()).removeByCallId(any());
        }

        @Test
        @DisplayName("Should delegate heartbeat refresh, presence query, and skill lookup")
        void shouldDelegatePresenceQueriesAndHeartbeat() {
            OperatorPresence op = availableOperator("op-hb-1", SkillGroup.DISRUPTION_DESK_STANDARD);
            Instant beatAt = NOW.plusSeconds(10);

            when(operatorPresencePort.refreshHeartbeat(op.operatorId(), beatAt)).thenReturn(true);
            when(operatorPresencePort.findPresence(op.operatorId())).thenReturn(Optional.of(op));
            when(operatorPresencePort.findBySkillGroup(SkillGroup.DISRUPTION_DESK_STANDARD))
                    .thenReturn(List.of(op));

            Optional<OperatorPresence> refreshed =
                    coordinator.refreshHeartbeat(op.operatorId(), beatAt);
            assertThat(refreshed).isPresent();
            assertThat(refreshed.get().lastHeartbeatAt()).isEqualTo(beatAt);
            assertThat(coordinator.getPresence(op.operatorId())).contains(op);
            assertThat(coordinator.listOperatorsBySkillGroup(SkillGroup.DISRUPTION_DESK_STANDARD))
                    .containsExactly(op);
        }
    }

    @Nested
    @DisplayName("CompleteHandoffUseCase Completion & Abandonment")
    class CompleteAndAbandonTests {

        @Test
        @DisplayName("Should complete handoff, release operator, and trigger auto queue-drain")
        void shouldCompleteHandoffAndDrainNextQueuedCall() {
            OperatorId operatorId = OperatorId.fromString("op-comp-1");
            OperatorPresence busyOp =
                    availableOperator("op-comp-1", SkillGroup.DISRUPTION_DESK_STANDARD)
                            .assignCall(CallId.generate(), NOW.minusSeconds(60));
            CallId completedCallId = busyOp.activeCallId().orElseThrow();
            TicketFormFilledEvent completedTicket =
                    sampleTicket("DISRUPTION_DESK_STANDARD", false, false);
            TicketFormFilledEvent queuedTicket =
                    sampleTicket("DISRUPTION_DESK_STANDARD", false, false);
            QueuedTicketItem queuedNext =
                    new QueuedTicketItem(
                            queuedTicket,
                            SkillGroup.DISRUPTION_DESK_STANDARD,
                            50,
                            NOW.minusSeconds(15));

            when(ticketPersistencePort.findTicketByCallId(completedCallId))
                    .thenReturn(Optional.of(completedTicket));
            when(operatorPresencePort.findPresence(operatorId)).thenReturn(Optional.of(busyOp));
            when(priorityCallQueuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_STANDARD))
                    .thenReturn(Optional.of(queuedNext));
            when(operatorPresencePort.tryClaimOperator(operatorId, queuedTicket.callId(), NOW))
                    .thenReturn(true);
            when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_STANDARD))
                    .thenReturn(0L);

            HandoffLifecycleState result =
                    coordinator.completeHandoff(
                            completedCallId, operatorId, "Rebooked on SW-208", NOW);

            assertThat(result)
                    .isEqualTo(
                            new HandoffLifecycleState.Completed(
                                    completedCallId,
                                    completedTicket.ticketId(),
                                    operatorId,
                                    "Rebooked on SW-208",
                                    NOW));

            InOrder ordered =
                    inOrder(ticketPersistencePort, operatorPresencePort, operatorDispatchPort);
            ordered.verify(ticketPersistencePort)
                    .updateTicketStatus(completedTicket.ticketId(), "COMPLETED", NOW);
            ordered.verify(operatorPresencePort)
                    .releaseOperator(operatorId, OperatorAvailabilityStatus.AVAILABLE, NOW);
            ordered.verify(operatorDispatchPort).notifyHandoffStateChanged(result);
            ordered.verify(operatorPresencePort)
                    .tryClaimOperator(operatorId, queuedTicket.callId(), NOW);

            verify(audioRerouteSignalPort).sendRerouteSignal(signalCaptor.capture());
            assertThat(signalCaptor.getValue().callId()).isEqualTo(queuedTicket.callId());
        }

        @Test
        @DisplayName("Should mark queued call as abandoned, broadcast queue depth, and audit log")
        void shouldMarkQueuedCallAsAbandoned() {
            TicketFormFilledEvent ticket = sampleTicket("DISRUPTION_DESK_STANDARD", false, false);
            QueuedTicketItem queued =
                    new QueuedTicketItem(
                            ticket, SkillGroup.DISRUPTION_DESK_STANDARD, 50, NOW.minusSeconds(10));

            when(priorityCallQueuePort.removeByCallId(ticket.callId()))
                    .thenReturn(Optional.of(queued));
            when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_STANDARD))
                    .thenReturn(0L);
            when(ticketPersistencePort.findTicketByCallId(ticket.callId()))
                    .thenReturn(Optional.of(ticket));

            HandoffLifecycleState result =
                    coordinator.markCallAbandoned(ticket.callId(), "CALLER_HANGUP", NOW);

            assertThat(result)
                    .isEqualTo(
                            new HandoffLifecycleState.Abandoned(
                                    ticket.callId(), ticket.ticketId(), "CALLER_HANGUP", NOW));

            verify(operatorDispatchPort)
                    .broadcastQueueMetrics(SkillGroup.DISRUPTION_DESK_STANDARD, 0L);
            verify(ticketPersistencePort).updateTicketStatus(ticket.ticketId(), "ABANDONED", NOW);
            verify(ticketPersistencePort)
                    .recordHandoffAuditLog(
                            eq(ticket.callId()),
                            eq("CALL_ABANDONED"),
                            eq("QUEUED"),
                            eq("ABANDONED"),
                            isNull(),
                            anyMap(),
                            eq(NOW));
            verify(operatorDispatchPort).notifyHandoffStateChanged(result);
        }
    }
}
