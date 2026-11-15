package com.voicepulse.routing.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.inbound.ClaimQueuedCallUseCase;
import com.voicepulse.routing.domain.ports.inbound.CompleteHandoffUseCase;
import com.voicepulse.routing.domain.ports.inbound.InspectRoutingQueueUseCase;
import com.voicepulse.routing.domain.ports.inbound.ManageOperatorPresenceUseCase;
import com.voicepulse.routing.infrastructure.ws.config.OperatorConsoleProperties;
import com.voicepulse.routing.infrastructure.ws.dto.ApiErrorDto;
import com.voicepulse.routing.infrastructure.ws.dto.ClaimCallRequestDto;
import com.voicepulse.routing.infrastructure.ws.dto.CompleteHandoffRequestDto;
import com.voicepulse.routing.infrastructure.ws.dto.ConsoleBootstrapConfigDto;
import com.voicepulse.routing.infrastructure.ws.dto.HandoffStateDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorAssignmentPayloadDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorPresenceDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorPresenceRequestDto;
import com.voicepulse.routing.infrastructure.ws.dto.QueueOverviewDto;
import com.voicepulse.routing.infrastructure.ws.mapper.OperatorConsoleMapperImpl;
import com.voicepulse.routing.infrastructure.ws.mapper.TranscriptContextExtractor;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = OperatorRestController.class)
@Import({
    OperatorRestExceptionHandler.class,
    OperatorConsoleMapperImpl.class,
    TranscriptContextExtractor.class,
    VirtualThreadTaskOffloader.class,
    OperatorConsoleSessionRegistry.class,
    OperatorRestControllerTest.TestClockConfig.class
})
@TestPropertySource(
        properties = {
            "voicepulse.routing.console.gateway-ws-base-url=ws://localhost:8080",
            "voicepulse.routing.console.heartbeat-interval=10s",
            "voicepulse.routing.console.outbound-buffer-capacity=128"
        })
@DisplayName("OperatorRestController WebFlux Slice Tests")
class OperatorRestControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    @TestConfiguration
    @EnableConfigurationProperties(OperatorConsoleProperties.class)
    static class TestClockConfig {

        @Bean
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private WebTestClient webTestClient;

    @MockitoBean private ManageOperatorPresenceUseCase manageOperatorPresenceUseCase;
    @MockitoBean private ClaimQueuedCallUseCase claimQueuedCallUseCase;
    @MockitoBean private CompleteHandoffUseCase completeHandoffUseCase;
    @MockitoBean private InspectRoutingQueueUseCase inspectRoutingQueueUseCase;

    private static TicketFormFilledEvent sampleTicket() {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::passengerName), "Lucia Fernandez")
                .set(field(TicketFormFilledEvent::pnr), new Pnr("AB7DF9"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(
                        field(TicketFormFilledEvent::targetSkillGroup),
                        SkillGroup.DISRUPTION_DESK_GOLD.name())
                .set(
                        field(TicketFormFilledEvent::payload),
                        Map.of(
                                "summary",
                                "Canceled flight SW-104",
                                "transcript",
                                List.of("CALLER: My flight was canceled.")))
                .set(field(TicketFormFilledEvent::timestamp), NOW)
                .create();
    }

    @Test
    @DisplayName("GET /api/operators/console/config returns bootstrap settings")
    void returnsBootstrapConfig() {
        webTestClient
                .get()
                .uri("/api/operators/console/config")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(ConsoleBootstrapConfigDto.class)
                .value(
                        cfg -> {
                            assertThat(cfg.gatewayWsBaseUrl()).isEqualTo("ws://localhost:8080");
                            assertThat(cfg.heartbeatIntervalMs()).isEqualTo(10_000L);
                            assertThat(cfg.skillGroups())
                                    .contains("DISRUPTION_DESK_GOLD", "DISRUPTION_DESK_STANDARD");
                            assertThat(cfg.availabilityStatuses())
                                    .contains("AVAILABLE", "PAUSED", "OFFLINE", "BUSY");
                        });
    }

    @Test
    @DisplayName("PUT /api/operators/{id}/presence and POST heartbeat manage operator state")
    void updatesPresenceAndHeartbeat() {
        OperatorId opId = OperatorId.fromString("op-alex-1");
        OperatorPresence presence =
                new OperatorPresence(
                        opId,
                        "Alex Rivera",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.empty(),
                        NOW);
        when(manageOperatorPresenceUseCase.updatePresence(
                        eq(opId),
                        eq("Alex Rivera"),
                        eq(OperatorAvailabilityStatus.AVAILABLE),
                        eq(Set.of(SkillGroup.DISRUPTION_DESK_GOLD)),
                        eq(NOW)))
                .thenReturn(presence);
        when(manageOperatorPresenceUseCase.refreshHeartbeat(opId, NOW))
                .thenReturn(Optional.of(presence));
        when(manageOperatorPresenceUseCase.refreshHeartbeat(
                        OperatorId.fromString("missing-op"), NOW))
                .thenReturn(Optional.empty());

        webTestClient
                .put()
                .uri("/api/operators/op-alex-1/presence")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        new OperatorPresenceRequestDto(
                                "Alex Rivera", "AVAILABLE", Set.of("DISRUPTION_DESK_GOLD")))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(OperatorPresenceDto.class)
                .value(
                        dto -> {
                            assertThat(dto.operatorId()).isEqualTo("op-alex-1");
                            assertThat(dto.status()).isEqualTo("AVAILABLE");
                        });

        webTestClient
                .post()
                .uri("/api/operators/op-alex-1/heartbeat")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(OperatorPresenceDto.class)
                .value(dto -> assertThat(dto.operatorId()).isEqualTo("op-alex-1"));

        webTestClient
                .post()
                .uri("/api/operators/missing-op/heartbeat")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(ApiErrorDto.class)
                .value(err -> assertThat(err.status()).isEqualTo(404));
    }

    @Test
    @DisplayName("GET /api/operators/queue returns filtered or global queue overview")
    void returnsQueueOverview() {
        TicketFormFilledEvent ticket = sampleTicket();
        QueuedTicketItem item =
                new QueuedTicketItem(ticket, SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW);
        when(inspectRoutingQueueUseCase.queueDepthsBySkillGroup())
                .thenReturn(Map.of(SkillGroup.DISRUPTION_DESK_GOLD, 1L));
        when(inspectRoutingQueueUseCase.listQueuedBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .thenReturn(List.of(item));

        webTestClient
                .get()
                .uri("/api/operators/queue?skillGroup=DISRUPTION_DESK_GOLD")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(QueueOverviewDto.class)
                .value(
                        overview -> {
                            assertThat(overview.selectedSkillGroup())
                                    .isEqualTo("DISRUPTION_DESK_GOLD");
                            assertThat(overview.totalQueued()).isEqualTo(1L);
                            assertThat(overview.queuedCalls()).hasSize(1);
                            assertThat(overview.queuedCalls().getFirst().pnr()).isEqualTo("AB7DF9");
                        });
    }

    @Test
    @DisplayName(
            "POST /api/operators/{id}/claim handles 200 OK, 204 No Content, and 404/409 errors")
    void claimsQueuedCalls() {
        OperatorId opId = OperatorId.fromString("op-alex-1");
        OperatorPresence available =
                new OperatorPresence(
                        opId,
                        "Alex Rivera",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.empty(),
                        NOW);
        TicketFormFilledEvent ticket = sampleTicket();
        CallId callId = ticket.callId();
        HandoffAssignment assignment =
                new HandoffAssignment(
                        callId,
                        ticket.ticketId(),
                        opId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        "/ws/operators/op-alex-1/calls/" + callId + "/audio",
                        NOW);

        when(manageOperatorPresenceUseCase.getPresence(opId)).thenReturn(Optional.of(available));
        when(claimQueuedCallUseCase.claimSpecificCall(opId, callId, NOW))
                .thenReturn(Optional.of(assignment));
        when(inspectRoutingQueueUseCase.findTicketByCallId(callId)).thenReturn(Optional.of(ticket));

        webTestClient
                .post()
                .uri("/api/operators/op-alex-1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ClaimCallRequestDto(callId.toString()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(OperatorAssignmentPayloadDto.class)
                .value(
                        dto -> {
                            assertThat(dto.callId()).isEqualTo(callId.toString());
                            assertThat(dto.bridgeUrl())
                                    .isEqualTo(
                                            "ws://localhost:8080/ws/operators/op-alex-1/calls/"
                                                    + callId
                                                    + "/audio");
                            assertThat(dto.transcript()).hasSize(1);
                        });

        // Empty queue returns 204 No Content
        when(claimQueuedCallUseCase.claimNextQueuedCall(opId, NOW)).thenReturn(Optional.empty());
        webTestClient
                .post()
                .uri("/api/operators/op-alex-1/claim")
                .exchange()
                .expectStatus()
                .isNoContent();

        // Missing specific callId returns 404
        CallId missingCall = CallId.generate();
        when(claimQueuedCallUseCase.claimSpecificCall(opId, missingCall, NOW))
                .thenReturn(Optional.empty());
        webTestClient
                .post()
                .uri("/api/operators/op-alex-1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ClaimCallRequestDto(missingCall.toString()))
                .exchange()
                .expectStatus()
                .isNotFound();

        // BUSY operator returns 409 Conflict
        OperatorPresence busy = available.assignCall(callId, NOW);
        when(manageOperatorPresenceUseCase.getPresence(opId)).thenReturn(Optional.of(busy));
        webTestClient
                .post()
                .uri("/api/operators/op-alex-1/claim")
                .exchange()
                .expectStatus()
                .isEqualTo(409);
    }

    @Test
    @DisplayName("POST /complete and GET /ticket return structured handoff and ticket payloads")
    void completesHandoffAndFetchesTicket() {
        OperatorId opId = OperatorId.fromString("op-alex-1");
        TicketFormFilledEvent ticket = sampleTicket();
        CallId callId = ticket.callId();

        when(completeHandoffUseCase.completeHandoff(
                        eq(callId), eq(opId), eq("Rebooked on SW-210"), eq(NOW)))
                .thenReturn(
                        new HandoffLifecycleState.Completed(
                                callId, ticket.ticketId(), opId, "Rebooked on SW-210", NOW));
        when(inspectRoutingQueueUseCase.findTicketByCallId(callId)).thenReturn(Optional.of(ticket));

        webTestClient
                .post()
                .uri("/api/operators/op-alex-1/calls/" + callId + "/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CompleteHandoffRequestDto("Rebooked on SW-210"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(HandoffStateDto.class)
                .value(
                        state -> {
                            assertThat(state.status()).isEqualTo("COMPLETED");
                            assertThat(state.resolutionNotes()).isEqualTo("Rebooked on SW-210");
                        });

        webTestClient
                .get()
                .uri("/api/operators/calls/" + callId + "/ticket")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(OperatorAssignmentPayloadDto.class)
                .value(dto -> assertThat(dto.pnr()).isEqualTo("AB7DF9"));

        webTestClient
                .get()
                .uri("/api/operators/calls/not-a-uuid/ticket")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ApiErrorDto.class)
                .value(err -> assertThat(err.status()).isEqualTo(400));
    }
}
