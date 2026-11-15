package com.voicepulse.routing.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.ws.config.OperatorConsoleProperties;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorWsMessage;
import com.voicepulse.routing.infrastructure.ws.mapper.OperatorConsoleMapper;
import com.voicepulse.routing.infrastructure.ws.mapper.TranscriptContextExtractor;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketOperatorDispatchAdapter Tests")
class WebSocketOperatorDispatchAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-15T09:15:00Z");

    @Mock private OperatorConsoleSessionRegistry sessionRegistry;

    @Captor private ArgumentCaptor<OperatorWsMessage> messageCaptor;

    private WebSocketOperatorDispatchAdapter adapter;

    @BeforeEach
    void setUp() {
        OperatorConsoleMapper mapper = Mappers.getMapper(OperatorConsoleMapper.class);
        TranscriptContextExtractor extractor = new TranscriptContextExtractor();
        OperatorConsoleProperties props =
                new OperatorConsoleProperties("ws://localhost:8080", Duration.ofSeconds(10), 256);
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        adapter =
                new WebSocketOperatorDispatchAdapter(
                        sessionRegistry, mapper, extractor, props, fixedClock);
    }

    @Test
    @DisplayName("Caches and pushes AssignmentPushedMessage with composed bridgeUrl")
    void pushesAssignmentAndCachesActivePayload() {
        TicketFormFilledEvent ticket =
                Instancio.of(TicketFormFilledEvent.class)
                        .set(field(TicketFormFilledEvent::passengerName), "Maria Lopez")
                        .set(field(TicketFormFilledEvent::pnr), new Pnr("AB7DF9"))
                        .set(
                                field(TicketFormFilledEvent::originalFlightNumber),
                                new FlightNumber("SW-104"))
                        .set(
                                field(TicketFormFilledEvent::targetSkillGroup),
                                SkillGroup.DISRUPTION_DESK_GOLD.name())
                        .set(
                                field(TicketFormFilledEvent::payload),
                                Map.of("transcript", List.of("CALLER: Please help.")))
                        .set(field(TicketFormFilledEvent::timestamp), NOW)
                        .create();
        OperatorId operatorId = OperatorId.fromString("op-gold-1");
        HandoffAssignment assignment =
                new HandoffAssignment(
                        ticket.callId(),
                        ticket.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        "/ws/operators/op-gold-1/calls/" + ticket.callId() + "/audio",
                        NOW);

        adapter.pushAssignment(assignment, ticket);

        verify(sessionRegistry)
                .cacheActiveAssignment(
                        eq(operatorId),
                        argThat(
                                dto ->
                                        dto.pnr().equals("AB7DF9")
                                                && dto.bridgeUrl()
                                                        .startsWith(
                                                                "ws://localhost:8080/ws/operators/")));
        verify(sessionRegistry).sendToOperator(eq(operatorId), messageCaptor.capture());

        assertThat(messageCaptor.getValue())
                .isInstanceOf(OperatorWsMessage.AssignmentPushedMessage.class);
        OperatorWsMessage.AssignmentPushedMessage pushed =
                (OperatorWsMessage.AssignmentPushedMessage) messageCaptor.getValue();
        assertThat(pushed.assignment().transcript()).hasSize(1);
    }

    @Test
    @DisplayName(
            "Broadcasts QueueMetricsMessage and handles lifecycle state transitions + eviction")
    void broadcastsMetricsAndRoutesStateChanges() {
        adapter.broadcastQueueMetrics(SkillGroup.DISRUPTION_DESK_GOLD, 4L);

        verify(sessionRegistry).broadcast(messageCaptor.capture());
        assertThat(messageCaptor.getValue())
                .isInstanceOf(OperatorWsMessage.QueueMetricsMessage.class);
        OperatorWsMessage.QueueMetricsMessage metrics =
                (OperatorWsMessage.QueueMetricsMessage) messageCaptor.getValue();
        assertThat(metrics.skillGroup()).isEqualTo("DISRUPTION_DESK_GOLD");
        assertThat(metrics.queueDepth()).isEqualTo(4L);

        CallId callId = CallId.generate();
        OperatorId opId = OperatorId.fromString("op-1");
        HandoffLifecycleState.Completed completed =
                new HandoffLifecycleState.Completed(callId, callId.value(), opId, "Done", NOW);
        adapter.notifyHandoffStateChanged(completed);

        verify(sessionRegistry).evictActiveAssignment(opId, callId);
        verify(sessionRegistry)
                .sendToOperator(eq(opId), isA(OperatorWsMessage.HandoffStateChangedMessage.class));

        CallId abandonedCall = CallId.generate();
        HandoffLifecycleState.Abandoned abandoned =
                new HandoffLifecycleState.Abandoned(
                        abandonedCall, abandonedCall.value(), "CALLER_HANGUP", NOW);
        adapter.notifyHandoffStateChanged(abandoned);

        verify(sessionRegistry).evictActiveAssignmentByCallId(abandonedCall);
    }
}
