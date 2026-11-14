package com.voicepulse.routing.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.model.SkillMatchPlan;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
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
import java.util.UUID;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("HandoffPersistenceCoordinator Domain Unit Tests")
class HandoffPersistenceCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");

    @Mock private TicketPersistencePort ticketPersistencePort;
    @Mock private OperatorPresencePort operatorPresencePort;
    @Mock private PriorityCallQueuePort priorityCallQueuePort;

    @Captor private ArgumentCaptor<Map<String, Object>> detailsCaptor;

    private HandoffPersistenceCoordinator persistenceCoordinator;

    @BeforeEach
    void setUp() {
        persistenceCoordinator =
                new HandoffPersistenceCoordinator(
                        ticketPersistencePort,
                        operatorPresencePort,
                        priorityCallQueuePort,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TicketFormFilledEvent sampleTicket() {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(field(TicketFormFilledEvent::targetSkillGroup), "DISRUPTION_DESK_GOLD")
                .set(
                        field(TicketFormFilledEvent::disruptionReason),
                        DisruptionReason.DELAYED_MAINTENANCE)
                .set(field(TicketFormFilledEvent::payload), Map.of())
                .create();
    }

    @Test
    @DisplayName("Should save immediate assignment and record audit metadata")
    void shouldSaveImmediateAssignmentAndAudit() {
        TicketFormFilledEvent event = sampleTicket();
        SkillMatchPlan plan =
                new SkillMatchPlan(
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        List.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        100);
        OperatorId operatorId = OperatorId.fromString("op-gold-1");
        HandoffAssignment assignment =
                new HandoffAssignment(
                        event.callId(),
                        event.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        "/ws/operators/op-gold-1/calls/" + event.callId() + "/audio",
                        NOW);
        HandoffLifecycleState.Assigned assignedState =
                new HandoffLifecycleState.Assigned(
                        event.callId(),
                        event.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        NOW);

        persistenceCoordinator.saveImmediateAssignment(event, plan, assignment, assignedState, NOW);

        verify(ticketPersistencePort)
                .saveTicket(event, SkillGroup.DISRUPTION_DESK_GOLD, assignedState);
        verify(ticketPersistencePort)
                .recordHandoffAuditLog(
                        eq(event.callId()),
                        eq("OPERATOR_ASSIGNED"),
                        isNull(),
                        eq("ASSIGNED"),
                        eq(operatorId),
                        detailsCaptor.capture(),
                        eq(NOW));
        assertThat(detailsCaptor.getValue())
                .containsEntry("ticketId", event.ticketId().toString())
                .containsEntry("skillGroup", "DISRUPTION_DESK_GOLD")
                .containsEntry("priorityScore", 100);
    }

    @Test
    @DisplayName("Should release claimed operator when immediate assignment persistence fails")
    void shouldReleaseOperatorWhenImmediatePersistenceFails() {
        TicketFormFilledEvent event = sampleTicket();
        SkillMatchPlan plan =
                new SkillMatchPlan(
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        List.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        100);
        OperatorId operatorId = OperatorId.fromString("op-gold-2");
        HandoffAssignment assignment =
                new HandoffAssignment(
                        event.callId(),
                        event.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        "/ws/operators/op-gold-2/calls/" + event.callId() + "/audio",
                        NOW);
        HandoffLifecycleState.Assigned assignedState =
                new HandoffLifecycleState.Assigned(
                        event.callId(),
                        event.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        NOW);

        doThrow(new IllegalStateException("DB error"))
                .when(ticketPersistencePort)
                .saveTicket(event, SkillGroup.DISRUPTION_DESK_GOLD, assignedState);

        assertThatThrownBy(
                        () ->
                                persistenceCoordinator.saveImmediateAssignment(
                                        event, plan, assignment, assignedState, NOW))
                .isInstanceOf(IllegalStateException.class);

        verify(operatorPresencePort)
                .releaseOperator(operatorId, OperatorAvailabilityStatus.AVAILABLE, NOW);
    }

    @Test
    @DisplayName("Should remove queued item as compensation when queued ticket persistence fails")
    void shouldRemoveQueuedItemWhenQueuedPersistenceFails() {
        TicketFormFilledEvent event = sampleTicket();
        SkillMatchPlan plan =
                new SkillMatchPlan(
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        List.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        100);
        HandoffLifecycleState.Queued queuedState =
                new HandoffLifecycleState.Queued(
                        event.callId(), event.ticketId(), SkillGroup.DISRUPTION_DESK_GOLD, 0, NOW);

        doThrow(new IllegalStateException("DB error"))
                .when(ticketPersistencePort)
                .saveTicket(event, SkillGroup.DISRUPTION_DESK_GOLD, queuedState);

        assertThatThrownBy(
                        () ->
                                persistenceCoordinator.saveQueuedTicket(
                                        event, plan, queuedState, 0, NOW))
                .isInstanceOf(IllegalStateException.class);

        verify(priorityCallQueuePort).removeByCallId(event.callId());
    }

    @Test
    @DisplayName("Should release operator and re-enqueue item when queued claim persistence fails")
    void shouldCompensateOperatorAndQueueWhenClaimPersistenceFails() {
        TicketFormFilledEvent event = sampleTicket();
        QueuedTicketItem queuedItem =
                new QueuedTicketItem(
                        event, SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW.minusSeconds(10));
        OperatorId operatorId = OperatorId.fromString("op-gold-3");
        HandoffAssignment assignment =
                new HandoffAssignment(
                        event.callId(),
                        event.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        "/ws/operators/op-gold-3/calls/" + event.callId() + "/audio",
                        NOW);

        doThrow(new IllegalStateException("DB failure"))
                .when(ticketPersistencePort)
                .updateTicketStatus(event.ticketId(), "ASSIGNED", NOW);

        assertThatThrownBy(
                        () -> persistenceCoordinator.recordQueuedClaim(queuedItem, assignment, NOW))
                .isInstanceOf(IllegalStateException.class);

        verify(operatorPresencePort)
                .releaseOperator(operatorId, OperatorAvailabilityStatus.AVAILABLE, NOW);
        verify(priorityCallQueuePort).enqueue(queuedItem);
    }

    @Test
    @DisplayName("Should record completion and abandonment audit logs accurately")
    void shouldRecordCompletionAndAbandonmentAuditLogs() {
        TicketFormFilledEvent completedEvent = sampleTicket();
        OperatorId operatorId = OperatorId.fromString("op-gold-4");
        when(ticketPersistencePort.findTicketByCallId(completedEvent.callId()))
                .thenReturn(Optional.of(completedEvent));

        UUID completedTicketId =
                persistenceCoordinator.recordHandoffCompletion(
                        completedEvent.callId(), operatorId, "Rebooked SW-205", NOW);

        assertThat(completedTicketId).isEqualTo(completedEvent.ticketId());
        verify(ticketPersistencePort)
                .updateTicketStatus(completedEvent.ticketId(), "COMPLETED", NOW);
        verify(ticketPersistencePort)
                .recordHandoffAuditLog(
                        eq(completedEvent.callId()),
                        eq("HANDOFF_COMPLETED"),
                        eq("ASSIGNED"),
                        eq("COMPLETED"),
                        eq(operatorId),
                        anyMap(),
                        eq(NOW));

        CallId abandonedCallId = CallId.generate();
        TicketFormFilledEvent abandonedEvent = sampleTicket();
        QueuedTicketItem removedQueuedItem =
                new QueuedTicketItem(abandonedEvent, SkillGroup.DISRUPTION_DESK_STANDARD, 50, NOW);
        when(ticketPersistencePort.findTicketByCallId(abandonedCallId))
                .thenReturn(Optional.empty());

        UUID abandonedTicketId =
                persistenceCoordinator.recordAbandonment(
                        abandonedCallId,
                        Optional.of(removedQueuedItem),
                        "CALLER_DISCONNECTED",
                        NOW);

        assertThat(abandonedTicketId).isEqualTo(removedQueuedItem.ticketId());
        verify(ticketPersistencePort)
                .updateTicketStatus(removedQueuedItem.ticketId(), "ABANDONED", NOW);
        verify(ticketPersistencePort)
                .recordHandoffAuditLog(
                        eq(abandonedCallId),
                        eq("CALL_ABANDONED"),
                        eq("QUEUED"),
                        eq("ABANDONED"),
                        isNull(),
                        anyMap(),
                        eq(NOW));
    }
}
