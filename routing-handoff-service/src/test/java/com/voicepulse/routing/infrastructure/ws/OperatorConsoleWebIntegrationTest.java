package com.voicepulse.routing.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.routing.RoutingHandoffApplication;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.AudioRerouteSignalPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
import com.voicepulse.routing.infrastructure.ws.dto.ClaimCallRequestDto;
import com.voicepulse.routing.infrastructure.ws.dto.CompleteHandoffRequestDto;
import com.voicepulse.routing.infrastructure.ws.dto.ConsoleBootstrapConfigDto;
import com.voicepulse.routing.infrastructure.ws.dto.HandoffStateDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorAssignmentPayloadDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorPresenceDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorPresenceRequestDto;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@SpringBootTest(
        classes = RoutingHandoffApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Operator Console End-to-End Web & WebSocket Integration Tests")
class OperatorConsoleWebIntegrationTest {

    private static final String OPERATOR_RAW_ID = "op-live-1";
    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

    @LocalServerPort private int port;

    @Autowired private WebTestClient webTestClient;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private OperatorPresencePort operatorPresencePort;
    @MockitoBean private PriorityCallQueuePort priorityCallQueuePort;
    @MockitoBean private TicketPersistencePort ticketPersistencePort;
    @MockitoBean private AudioRerouteSignalPort audioRerouteSignalPort;

    private static TicketFormFilledEvent sampleTicket() {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::passengerName), "Elena Morales")
                .set(field(TicketFormFilledEvent::pnr), new Pnr("KL9MX4"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-204"))
                .set(
                        field(TicketFormFilledEvent::targetSkillGroup),
                        SkillGroup.DISRUPTION_DESK_GOLD.name())
                .set(
                        field(TicketFormFilledEvent::payload),
                        Map.of(
                                "transcript",
                                List.of(
                                        "AGENT: Welcome back to SkyWay.",
                                        "CALLER: Please rebook me."),
                                "disruptionReason",
                                "CANCELED_WEATHER"))
                .set(field(TicketFormFilledEvent::timestamp), NOW)
                .create();
    }

    @Test
    @DisplayName("Serves static Operator Console UI bundle and bootstrap configuration")
    void servesStaticConsoleAssetsAndConfig() {
        webTestClient
                .get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isTemporaryRedirect()
                .expectHeader()
                .valueEquals("Location", "/console/index.html");

        webTestClient
                .get()
                .uri("/console")
                .exchange()
                .expectStatus()
                .isTemporaryRedirect()
                .expectHeader()
                .valueEquals("Location", "/console/index.html");

        webTestClient
                .get()
                .uri("/console/index.html")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(html -> assertThat(html).contains("VoicePulse Operator Console"));

        webTestClient.get().uri("/console/style.css").exchange().expectStatus().isOk();

        webTestClient
                .get()
                .uri("/console/console.js")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(js -> assertThat(js).contains("/ws/operators/"));

        webTestClient
                .get()
                .uri("/console/audio-processor.worklet.js")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        worklet ->
                                assertThat(worklet).contains("linear-pcm16-downsampler-processor"));

        webTestClient
                .get()
                .uri("/api/operators/console/config")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(ConsoleBootstrapConfigDto.class)
                .value(
                        config -> {
                            assertThat(config.gatewayWsBaseUrl()).isEqualTo("ws://localhost:8080");
                            assertThat(config.heartbeatIntervalMs()).isEqualTo(10_000L);
                            assertThat(config.skillGroups())
                                    .contains(
                                            SkillGroup.DISRUPTION_DESK_GOLD.name(),
                                            SkillGroup.DISRUPTION_DESK_STANDARD.name());
                            assertThat(config.availabilityStatuses())
                                    .contains(
                                            OperatorAvailabilityStatus.AVAILABLE.name(),
                                            OperatorAvailabilityStatus.BUSY.name());
                        });
    }

    @Test
    @DisplayName("Streams live snapshot, heartbeat pong, claimed ticket dispatch, and completion")
    void liveWebSocketAndRestWorkflowEndToEnd() throws Exception {
        OperatorId operatorId = OperatorId.fromString(OPERATOR_RAW_ID);
        TicketFormFilledEvent ticket = sampleTicket();
        CallId callId = ticket.callId();
        QueuedTicketItem queuedItem =
                new QueuedTicketItem(ticket, SkillGroup.DISRUPTION_DESK_GOLD, 910, NOW);

        OperatorPresence availablePresence =
                new OperatorPresence(
                        operatorId,
                        "Elena Vance",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.empty(),
                        NOW);

        when(operatorPresencePort.findPresence(operatorId))
                .thenReturn(Optional.of(availablePresence));
        when(operatorPresencePort.refreshHeartbeat(eq(operatorId), any())).thenReturn(true);
        when(priorityCallQueuePort.listQueuedBySkillGroup(any())).thenReturn(List.of());
        when(priorityCallQueuePort.listQueuedBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .thenReturn(List.of(queuedItem));
        when(priorityCallQueuePort.queueDepth(any())).thenReturn(0L);
        when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_GOLD)).thenReturn(1L);
        when(priorityCallQueuePort.dequeueHighestPriority(any())).thenReturn(Optional.empty());
        when(ticketPersistencePort.findTicketByCallId(callId)).thenReturn(Optional.of(ticket));

        CopyOnWriteArrayList<JsonNode> receivedFrames = new CopyOnWriteArrayList<>();
        Sinks.Many<String> clientOutbound = Sinks.many().unicast().onBackpressureBuffer();
        ReactorNettyWebSocketClient wsClient = new ReactorNettyWebSocketClient();
        URI wsUri = URI.create("ws://localhost:" + port + "/ws/operators/" + OPERATOR_RAW_ID);

        Disposable sessionDisposable =
                wsClient.execute(
                                wsUri,
                                session -> {
                                    Mono<Void> inbound =
                                            session.receive()
                                                    .map(WebSocketMessage::getPayloadAsText)
                                                    .doOnNext(
                                                            json -> {
                                                                try {
                                                                    receivedFrames.add(
                                                                            objectMapper.readTree(
                                                                                    json));
                                                                } catch (Exception ignored) {
                                                                    // Assertion fails below if
                                                                    // invalid JSON arrives.
                                                                }
                                                            })
                                                    .then();
                                    Mono<Void> outbound =
                                            session.send(
                                                    clientOutbound
                                                            .asFlux()
                                                            .map(session::textMessage));
                                    return Mono.when(inbound, outbound);
                                })
                        .subscribe();

        try {
            verifySnapshotAndHeartbeat(receivedFrames, clientOutbound);
            verifyPresenceAndClaimDispatch(operatorId, callId, queuedItem, receivedFrames);
            verifyCompletionBroadcast(callId, receivedFrames);
        } finally {
            sessionDisposable.dispose();
        }
    }

    private void verifySnapshotAndHeartbeat(
            CopyOnWriteArrayList<JsonNode> receivedFrames, Sinks.Many<String> clientOutbound) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(receivedFrames)
                                        .anySatisfy(
                                                frame ->
                                                        assertThat(frame.path("type").asText())
                                                                .isEqualTo("CONSOLE_SNAPSHOT")));

        JsonNode snapshot =
                receivedFrames.stream()
                        .filter(f -> "CONSOLE_SNAPSHOT".equals(f.path("type").asText()))
                        .findFirst()
                        .orElseThrow();
        assertThat(snapshot.path("presence").path("operatorId").asText())
                .isEqualTo(OPERATOR_RAW_ID);

        clientOutbound.tryEmitNext("{\"type\":\"HEARTBEAT\"}");

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(receivedFrames)
                                        .anySatisfy(
                                                frame ->
                                                        assertThat(frame.path("type").asText())
                                                                .isEqualTo("PONG")));
    }

    private void verifyPresenceAndClaimDispatch(
            OperatorId operatorId,
            CallId callId,
            QueuedTicketItem queuedItem,
            CopyOnWriteArrayList<JsonNode> receivedFrames) {
        webTestClient
                .put()
                .uri("/api/operators/" + OPERATOR_RAW_ID + "/presence")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        new OperatorPresenceRequestDto(
                                "Elena Vance",
                                OperatorAvailabilityStatus.AVAILABLE.name(),
                                Set.of(SkillGroup.DISRUPTION_DESK_GOLD.name())))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(OperatorPresenceDto.class)
                .value(
                        dto -> {
                            assertThat(dto.operatorId()).isEqualTo(OPERATOR_RAW_ID);
                            assertThat(dto.status()).isEqualTo("AVAILABLE");
                        });

        when(priorityCallQueuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD))
                .thenReturn(Optional.of(queuedItem));
        when(operatorPresencePort.tryClaimOperator(eq(operatorId), eq(callId), any()))
                .thenReturn(true);
        when(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_GOLD)).thenReturn(0L);

        webTestClient
                .post()
                .uri("/api/operators/" + OPERATOR_RAW_ID + "/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ClaimCallRequestDto(null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(OperatorAssignmentPayloadDto.class)
                .value(
                        payload -> {
                            assertThat(payload.callId()).isEqualTo(callId.value().toString());
                            assertThat(payload.pnr()).isEqualTo("KL9MX4");
                            assertThat(payload.bridgeEndpoint())
                                    .isEqualTo(
                                            "/ws/operators/"
                                                    + OPERATOR_RAW_ID
                                                    + "/calls/"
                                                    + callId.value()
                                                    + "/audio");
                        });

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            assertThat(receivedFrames)
                                    .anySatisfy(
                                            frame -> {
                                                assertThat(frame.path("type").asText())
                                                        .isEqualTo("ASSIGNMENT");
                                                assertThat(
                                                                frame.path("assignment")
                                                                        .path("pnr")
                                                                        .asText())
                                                        .isEqualTo("KL9MX4");
                                                assertThat(
                                                                frame.path("assignment")
                                                                        .path("transcript")
                                                                        .size())
                                                        .isEqualTo(2);
                                            });
                            assertThat(receivedFrames)
                                    .anySatisfy(
                                            frame -> {
                                                assertThat(frame.path("type").asText())
                                                        .isEqualTo("QUEUE_METRICS");
                                                assertThat(frame.path("skillGroup").asText())
                                                        .isEqualTo(
                                                                SkillGroup.DISRUPTION_DESK_GOLD
                                                                        .name());
                                            });
                        });
    }

    private void verifyCompletionBroadcast(
            CallId callId, CopyOnWriteArrayList<JsonNode> receivedFrames) {
        when(priorityCallQueuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD))
                .thenReturn(Optional.empty());

        webTestClient
                .post()
                .uri("/api/operators/" + OPERATOR_RAW_ID + "/calls/" + callId.value() + "/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CompleteHandoffRequestDto("Rebooked passenger onto SW-210"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(HandoffStateDto.class)
                .value(
                        state -> {
                            assertThat(state.status()).isEqualTo("COMPLETED");
                            assertThat(state.resolutionNotes())
                                    .isEqualTo("Rebooked passenger onto SW-210");
                        });

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(receivedFrames)
                                        .anySatisfy(
                                                frame -> {
                                                    assertThat(frame.path("type").asText())
                                                            .isEqualTo("HANDOFF_STATE");
                                                    assertThat(
                                                                    frame.path("state")
                                                                            .path("status")
                                                                            .asText())
                                                            .isEqualTo("COMPLETED");
                                                }));
    }
}
