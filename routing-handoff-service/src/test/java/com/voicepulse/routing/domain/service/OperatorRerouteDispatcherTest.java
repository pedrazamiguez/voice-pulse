package com.voicepulse.routing.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.AudioRerouteSignalPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorDispatchPort;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.Map;
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
@DisplayName("OperatorRerouteDispatcher Domain Unit Tests")
class OperatorRerouteDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-15T00:05:00Z");

    @Mock private OperatorDispatchPort operatorDispatchPort;
    @Mock private AudioRerouteSignalPort audioRerouteSignalPort;

    @Captor private ArgumentCaptor<OperatorRerouteSignal> signalCaptor;

    private OperatorRerouteDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher =
                new OperatorRerouteDispatcher(
                        operatorDispatchPort, audioRerouteSignalPort, "/ws/operators/");
    }

    @Test
    @DisplayName("Should normalize bridge base path and reject invalid paths")
    void shouldValidateBridgeBasePath() {
        CallId callId = CallId.generate();
        OperatorId operatorId = OperatorId.fromString("op-disp-1");
        HandoffAssignment assignment =
                dispatcher.createAssignment(
                        callId,
                        UUID.randomUUID(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        NOW);

        assertThat(assignment.bridgeEndpoint())
                .isEqualTo("/ws/operators/op-disp-1/calls/" + callId + "/audio");

        assertThatThrownBy(
                        () ->
                                new OperatorRerouteDispatcher(
                                        operatorDispatchPort,
                                        audioRerouteSignalPort,
                                        "ws/no-slash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new OperatorRerouteDispatcher(
                                        operatorDispatchPort, audioRerouteSignalPort, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should dispatch assignment notifications and monotonic reroute signals")
    void shouldDispatchAssignmentAndIncrementSequence() {
        TicketFormFilledEvent event =
                Instancio.of(TicketFormFilledEvent.class)
                        .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                        .set(
                                field(TicketFormFilledEvent::originalFlightNumber),
                                new FlightNumber("SW-104"))
                        .set(field(TicketFormFilledEvent::payload), Map.of())
                        .create();
        OperatorId operatorId = OperatorId.fromString("op-disp-2");
        HandoffAssignment firstAssignment =
                dispatcher.createAssignment(
                        event.callId(),
                        event.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        NOW);
        HandoffLifecycleState.Assigned assignedState =
                new HandoffLifecycleState.Assigned(
                        event.callId(),
                        event.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        NOW);

        dispatcher.dispatchAssignment(firstAssignment, event, assignedState, NOW);
        dispatcher.dispatchAssignment(firstAssignment, event, assignedState, NOW.plusSeconds(1));

        verify(operatorDispatchPort, times(2)).pushAssignment(firstAssignment, event);
        verify(operatorDispatchPort, times(2)).notifyHandoffStateChanged(assignedState);
        verify(audioRerouteSignalPort, times(2)).sendRerouteSignal(signalCaptor.capture());

        assertThat(signalCaptor.getAllValues())
                .extracting(OperatorRerouteSignal::sequenceNumber)
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("Should delegate lifecycle state and queue depth broadcasts")
    void shouldBroadcastLifecycleAndQueueMetrics() {
        HandoffLifecycleState.Queued queuedState =
                new HandoffLifecycleState.Queued(
                        CallId.generate(),
                        UUID.randomUUID(),
                        SkillGroup.DISRUPTION_DESK_PRIORITY,
                        2,
                        NOW);

        dispatcher.notifyLifecycleState(queuedState);
        dispatcher.broadcastQueueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY, 5L);

        verify(operatorDispatchPort).notifyHandoffStateChanged(queuedState);
        verify(operatorDispatchPort).broadcastQueueMetrics(SkillGroup.DISRUPTION_DESK_PRIORITY, 5L);
    }
}
