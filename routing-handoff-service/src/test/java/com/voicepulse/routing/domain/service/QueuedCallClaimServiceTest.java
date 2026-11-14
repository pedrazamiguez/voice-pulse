package com.voicepulse.routing.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueuedCallClaimService Domain Unit Tests")
class QueuedCallClaimServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T00:20:00Z");

    @Mock private PriorityCallQueuePort priorityCallQueuePort;
    @Mock private QueuedCallDrainService queuedCallDrainService;

    private QueuedCallClaimService claimService;

    @BeforeEach
    void setUp() {
        claimService = new QueuedCallClaimService(priorityCallQueuePort, queuedCallDrainService);
    }

    @Test
    @DisplayName("Should drain next waiting call for available operator")
    void shouldClaimNextQueuedCall() {
        OperatorId operatorId = OperatorId.fromString("op-claim-1");
        Set<SkillGroup> skills = Set.of(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY);
        OperatorPresence presence =
                new OperatorPresence(
                        operatorId,
                        "Accessibility Agent",
                        OperatorAvailabilityStatus.AVAILABLE,
                        skills,
                        Optional.empty(),
                        NOW);
        CallId callId = CallId.generate();
        TicketFormFilledEvent event =
                Instancio.of(TicketFormFilledEvent.class)
                        .set(field(TicketFormFilledEvent::callId), callId)
                        .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                        .set(
                                field(TicketFormFilledEvent::originalFlightNumber),
                                new FlightNumber("SW-104"))
                        .set(field(TicketFormFilledEvent::payload), Map.of())
                        .create();
        HandoffAssignment assignment =
                new HandoffAssignment(
                        callId,
                        event.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_ACCESSIBILITY,
                        "/ws/operators/op-claim-1/calls/" + callId + "/audio",
                        NOW);

        when(queuedCallDrainService.findAvailableOperator(operatorId))
                .thenReturn(Optional.of(presence));
        when(queuedCallDrainService.drainNextForSkills(operatorId, skills, NOW))
                .thenReturn(Optional.of(assignment));

        Optional<HandoffAssignment> claimed = claimService.claimNextQueuedCall(operatorId, NOW);

        assertThat(claimed).contains(assignment);
        verifyNoInteractions(priorityCallQueuePort);
    }

    @Test
    @DisplayName("Should return empty when claiming next or specific call for unavailable operator")
    void shouldRejectClaimWhenOperatorUnavailable() {
        OperatorId operatorId = OperatorId.fromString("op-busy");
        CallId callId = CallId.generate();

        when(queuedCallDrainService.findAvailableOperator(operatorId)).thenReturn(Optional.empty());

        assertThat(claimService.claimNextQueuedCall(operatorId, NOW)).isEmpty();
        assertThat(claimService.claimSpecificCall(operatorId, callId, NOW)).isEmpty();
        verifyNoInteractions(priorityCallQueuePort);
    }

    @Test
    @DisplayName("Should remove and claim specific queued call for available operator")
    void shouldClaimSpecificQueuedCall() {
        OperatorId operatorId = OperatorId.fromString("op-claim-2");
        OperatorPresence presence =
                new OperatorPresence(
                        operatorId,
                        "Gold Agent",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.empty(),
                        NOW);
        TicketFormFilledEvent event =
                Instancio.of(TicketFormFilledEvent.class)
                        .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                        .set(
                                field(TicketFormFilledEvent::originalFlightNumber),
                                new FlightNumber("SW-104"))
                        .set(field(TicketFormFilledEvent::payload), Map.of())
                        .create();
        QueuedTicketItem queuedItem =
                new QueuedTicketItem(event, SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW);
        HandoffAssignment assignment =
                new HandoffAssignment(
                        event.callId(),
                        event.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        "/ws/operators/op-claim-2/calls/" + event.callId() + "/audio",
                        NOW);

        when(queuedCallDrainService.findAvailableOperator(operatorId))
                .thenReturn(Optional.of(presence));
        when(priorityCallQueuePort.removeByCallId(event.callId()))
                .thenReturn(Optional.of(queuedItem));
        when(queuedCallDrainService.claimQueuedItem(operatorId, queuedItem, NOW))
                .thenReturn(Optional.of(assignment));

        assertThat(claimService.claimSpecificCall(operatorId, event.callId(), NOW))
                .contains(assignment);

        CallId missingCallId = CallId.generate();
        when(priorityCallQueuePort.removeByCallId(missingCallId)).thenReturn(Optional.empty());
        assertThat(claimService.claimSpecificCall(operatorId, missingCallId, NOW)).isEmpty();
    }
}
