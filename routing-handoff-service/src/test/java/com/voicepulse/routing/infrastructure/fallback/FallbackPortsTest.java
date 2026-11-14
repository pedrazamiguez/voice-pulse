package com.voicepulse.routing.infrastructure.fallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.instancio.Select.field;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Null Object Fallback Ports Tests")
class FallbackPortsTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    private static TicketFormFilledEvent sampleTicketEvent() {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(field(TicketFormFilledEvent::targetSkillGroup), "DISRUPTION_DESK_GOLD")
                .set(field(TicketFormFilledEvent::payload), Map.of())
                .create();
    }

    @Test
    @DisplayName("NoOpProcessTicketIntentUseCase operates as a safe Null Object")
    void processTicketIntentFallback() {
        NoOpProcessTicketIntentUseCase useCase = new NoOpProcessTicketIntentUseCase();
        TicketFormFilledEvent event = sampleTicketEvent();

        HandoffLifecycleState state = useCase.processTicketIntent(event);
        assertThat(state).isInstanceOf(HandoffLifecycleState.Queued.class);
        assertThat(state.callId()).isEqualTo(event.callId());
    }

    @Test
    @DisplayName("NoOpManageOperatorPresenceUseCase operates as a safe Null Object")
    void manageOperatorPresenceFallback() {
        NoOpManageOperatorPresenceUseCase useCase = new NoOpManageOperatorPresenceUseCase();
        OperatorId operatorId = OperatorId.fromString("op-fallback-1");

        OperatorPresence presence =
                useCase.updatePresence(
                        operatorId,
                        "Fallback Operator",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_STANDARD),
                        NOW);
        assertThat(presence.operatorId()).isEqualTo(operatorId);
        assertThat(useCase.refreshHeartbeat(operatorId, NOW)).isEmpty();
        assertThat(useCase.getPresence(operatorId)).isEmpty();
        assertThat(useCase.listOperatorsBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD)).isEmpty();
    }

    @Test
    @DisplayName("NoOpClaimQueuedCallUseCase operates as a safe Null Object")
    void claimQueuedCallFallback() {
        NoOpClaimQueuedCallUseCase useCase = new NoOpClaimQueuedCallUseCase();
        OperatorId operatorId = OperatorId.fromString("op-fallback-1");
        CallId callId = CallId.generate();

        assertThat(useCase.claimNextQueuedCall(operatorId, NOW)).isEmpty();
        assertThat(useCase.claimSpecificCall(operatorId, callId, NOW)).isEmpty();
    }

    @Test
    @DisplayName("NoOpCompleteHandoffUseCase operates as a safe Null Object")
    void completeHandoffFallback() {
        NoOpCompleteHandoffUseCase useCase = new NoOpCompleteHandoffUseCase();
        CallId callId = CallId.generate();
        OperatorId operatorId = OperatorId.fromString("op-fallback-1");

        HandoffLifecycleState completed =
                useCase.completeHandoff(callId, operatorId, "Rebooked", NOW);
        assertThat(completed).isInstanceOf(HandoffLifecycleState.Completed.class);
        assertThat(completed.callId()).isEqualTo(callId);

        HandoffLifecycleState abandoned = useCase.markCallAbandoned(callId, "CALLER_HANGUP", NOW);
        assertThat(abandoned).isInstanceOf(HandoffLifecycleState.Abandoned.class);
        assertThat(abandoned.callId()).isEqualTo(callId);

        HandoffLifecycleState fallbackAbandoned = useCase.markCallAbandoned(callId, "  ", NOW);
        assertThat(fallbackAbandoned).isInstanceOf(HandoffLifecycleState.Abandoned.class);
    }

    @Test
    @DisplayName("NoOpOperatorPresencePort operates as a safe Null Object")
    void operatorPresencePortFallback() {
        NoOpOperatorPresencePort port = new NoOpOperatorPresencePort();
        OperatorId operatorId = OperatorId.fromString("op-fallback-1");
        OperatorPresence presence =
                new OperatorPresence(
                        operatorId,
                        "Fallback Operator",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.empty(),
                        NOW);

        assertThatCode(() -> port.savePresence(presence)).doesNotThrowAnyException();
        assertThat(port.findPresence(operatorId)).isEmpty();
        assertThat(port.findAvailableBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD)).isEmpty();
        assertThat(port.findBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD)).isEmpty();
        assertThat(port.tryClaimOperator(operatorId, CallId.generate(), NOW)).isFalse();
        assertThatCode(
                        () ->
                                port.releaseOperator(
                                        operatorId, OperatorAvailabilityStatus.AVAILABLE, NOW))
                .doesNotThrowAnyException();
        assertThat(port.refreshHeartbeat(operatorId, NOW)).isFalse();
    }

    @Test
    @DisplayName("NoOpPriorityCallQueuePort operates as a safe Null Object")
    void priorityCallQueuePortFallback() {
        NoOpPriorityCallQueuePort port = new NoOpPriorityCallQueuePort();
        QueuedTicketItem item =
                new QueuedTicketItem(
                        sampleTicketEvent(), SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW);

        assertThat(port.enqueue(item)).isZero();
        assertThat(port.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD)).isEmpty();
        assertThat(port.removeByCallId(item.callId())).isEmpty();
        assertThat(port.listQueuedBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD)).isEmpty();
        assertThat(port.queueDepth(SkillGroup.DISRUPTION_DESK_GOLD)).isZero();
    }

    @Test
    @DisplayName("NoOpTicketPersistencePort operates as a safe Null Object")
    void ticketPersistencePortFallback() {
        NoOpTicketPersistencePort port = new NoOpTicketPersistencePort();
        TicketFormFilledEvent event = sampleTicketEvent();
        HandoffLifecycleState initialState =
                new HandoffLifecycleState.Queued(
                        event.callId(), event.ticketId(), SkillGroup.DISRUPTION_DESK_GOLD, 1, NOW);

        assertThatCode(
                        () -> {
                            port.saveTicket(event, SkillGroup.DISRUPTION_DESK_GOLD, initialState);
                            port.updateTicketStatus(event.ticketId(), "ASSIGNED", NOW);
                            port.recordHandoffAuditLog(
                                    event.callId(),
                                    "TICKET_QUEUED",
                                    null,
                                    "QUEUED",
                                    null,
                                    Map.of("position", 1),
                                    NOW);
                        })
                .doesNotThrowAnyException();
        assertThat(port.findTicketByCallId(event.callId())).isEmpty();
    }

    @Test
    @DisplayName("NoOpOperatorDispatchPort operates as a safe Null Object")
    void operatorDispatchPortFallback() {
        NoOpOperatorDispatchPort port = new NoOpOperatorDispatchPort();
        TicketFormFilledEvent event = sampleTicketEvent();
        OperatorId operatorId = OperatorId.fromString("op-1");
        HandoffAssignment assignment =
                new HandoffAssignment(
                        event.callId(),
                        event.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        "/ws/operators/op-1/calls/" + event.callId() + "/audio",
                        NOW);
        HandoffLifecycleState state =
                new HandoffLifecycleState.Assigned(
                        event.callId(),
                        event.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        NOW);

        assertThatCode(
                        () -> {
                            port.pushAssignment(assignment, event);
                            port.broadcastQueueMetrics(SkillGroup.DISRUPTION_DESK_GOLD, 3L);
                            port.notifyHandoffStateChanged(state);
                        })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NoOpAudioRerouteSignalPort operates as a safe Null Object")
    void audioRerouteSignalPortFallback() {
        NoOpAudioRerouteSignalPort port = new NoOpAudioRerouteSignalPort();
        CallId callId = CallId.generate();
        OperatorRerouteSignal signal =
                new OperatorRerouteSignal(
                        callId, 1L, NOW, "op-1", "/ws/operators/op-1/calls/" + callId + "/audio");

        assertThatCode(() -> port.sendRerouteSignal(signal)).doesNotThrowAnyException();
    }
}
