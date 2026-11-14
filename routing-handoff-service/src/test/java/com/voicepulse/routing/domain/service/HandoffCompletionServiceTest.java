package com.voicepulse.routing.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("HandoffCompletionService Domain Unit Tests")
class HandoffCompletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T00:25:00Z");

    @Mock private OperatorPresencePort operatorPresencePort;
    @Mock private PriorityCallQueuePort priorityCallQueuePort;
    @Mock private HandoffPersistenceCoordinator persistenceCoordinator;
    @Mock private OperatorRerouteDispatcher dispatcher;
    @Mock private QueuedCallDrainService queuedCallDrainService;

    private HandoffCompletionService completionService;

    @BeforeEach
    void setUp() {
        completionService =
                new HandoffCompletionService(
                        operatorPresencePort,
                        priorityCallQueuePort,
                        persistenceCoordinator,
                        dispatcher,
                        queuedCallDrainService);
    }

    @Test
    @DisplayName("Should persist completion, release operator, notify dispatcher, and drain queue")
    void shouldCompleteHandoffReleaseAndDrain() {
        CallId callId = CallId.generate();
        UUID ticketId = UUID.randomUUID();
        OperatorId operatorId = OperatorId.fromString("op-comp-1");
        Set<SkillGroup> skills = Set.of(SkillGroup.DISRUPTION_DESK_GOLD);
        OperatorPresence presence =
                new OperatorPresence(
                        operatorId,
                        "Gold Agent",
                        OperatorAvailabilityStatus.BUSY,
                        skills,
                        Optional.of(callId),
                        NOW.minusSeconds(20));

        when(operatorPresencePort.findPresence(operatorId)).thenReturn(Optional.of(presence));
        when(persistenceCoordinator.recordHandoffCompletion(
                        callId, operatorId, "Rebooked SW-205", NOW))
                .thenReturn(ticketId);

        HandoffLifecycleState state =
                completionService.completeHandoff(callId, operatorId, "  Rebooked SW-205  ", NOW);

        assertThat(state)
                .isEqualTo(
                        new HandoffLifecycleState.Completed(
                                callId, ticketId, operatorId, "Rebooked SW-205", NOW));

        InOrder order =
                inOrder(
                        persistenceCoordinator,
                        operatorPresencePort,
                        dispatcher,
                        queuedCallDrainService);
        order.verify(persistenceCoordinator)
                .recordHandoffCompletion(callId, operatorId, "Rebooked SW-205", NOW);
        order.verify(operatorPresencePort)
                .releaseOperator(operatorId, OperatorAvailabilityStatus.AVAILABLE, NOW);
        order.verify(dispatcher).notifyLifecycleState(state);
        order.verify(queuedCallDrainService).drainNextForSkills(operatorId, skills, NOW);
    }

    @Test
    @DisplayName("Should dequeue abandoned call, default blank reason, and notify dispatcher")
    void shouldMarkCallAbandonedWithNormalizedReason() {
        TicketFormFilledEvent event =
                Instancio.of(TicketFormFilledEvent.class)
                        .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                        .set(
                                field(TicketFormFilledEvent::originalFlightNumber),
                                new FlightNumber("SW-104"))
                        .set(field(TicketFormFilledEvent::payload), Map.of())
                        .create();
        QueuedTicketItem queuedItem =
                new QueuedTicketItem(event, SkillGroup.DISRUPTION_DESK_PRIORITY, 80, NOW);

        when(priorityCallQueuePort.removeByCallId(event.callId()))
                .thenReturn(Optional.of(queuedItem));
        when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY)).thenReturn(1L);
        when(persistenceCoordinator.recordAbandonment(
                        event.callId(), Optional.of(queuedItem), "CALLER_DISCONNECTED", NOW))
                .thenReturn(event.ticketId());

        HandoffLifecycleState state =
                completionService.markCallAbandoned(event.callId(), "   ", NOW);

        assertThat(state)
                .isEqualTo(
                        new HandoffLifecycleState.Abandoned(
                                event.callId(), event.ticketId(), "CALLER_DISCONNECTED", NOW));
        verify(dispatcher).broadcastQueueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY, 1L);
        verify(dispatcher).notifyLifecycleState(state);
    }
}
