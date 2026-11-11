package com.voicepulse.conversation.infrastructure.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.TicketCategory;
import com.voicepulse.conversation.domain.model.TicketPriority;
import com.voicepulse.conversation.domain.model.TicketSubmissionReceipt;
import com.voicepulse.conversation.domain.ports.outbound.DisruptionTicketPort;
import com.voicepulse.conversation.infrastructure.fallback.NoOpDisruptionTicketPort;
import com.voicepulse.conversation.infrastructure.mcp.config.McpClientConfiguration;
import com.voicepulse.conversation.infrastructure.mcp.config.McpClientProperties;
import com.voicepulse.conversation.infrastructure.mcp.mapper.McpTicketMapper;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.instancio.Instancio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("McpTicketClientAdapter WireMock Integration Tests")
class McpTicketClientAdapterWireMockTest {

    private static final UUID FIXED_TICKET_ID =
            UUID.fromString("99214000-78d1-47fa-bf87-9bc40efb8911");

    private WireMockServer wireMockServer;
    private ExecutorService virtualThreadExecutor;
    private McpTicketClientAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        McpClientProperties properties =
                new McpClientProperties(
                        "http://localhost:8091",
                        wireMockServer.baseUrl(),
                        "http://localhost:8093",
                        Duration.ofSeconds(1),
                        Duration.ofMillis(400));
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .executor(virtualThreadExecutor)
                        .build();
        McpTicketMapper mapper = Mappers.getMapper(McpTicketMapper.class);

        adapter =
                new McpTicketClientAdapter(
                        httpClient,
                        virtualThreadExecutor,
                        KafkaJsonCodecProvider.objectMapper(),
                        properties,
                        mapper);
    }

    @AfterEach
    void tearDown() {
        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.close();
        }
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName(
            "200 OK finalize_ticket sends RFC-0003 §4.2 payload and maps TicketSubmissionReceipt")
    void finalizeTicket_when200Ok_sendsPayloadAndReturnsMappedReceipt() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/finalize_ticket"))
                        .withHeader("Content-Type", equalTo("application/json"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
{
  "ticket_id": "99214000-78d1-47fa-bf87-9bc40efb8911",
  "pnr": "A8F9X2",
  "category": "HOTEL_ACCOMMODATION",
  "priority": "HIGH",
  "status": "PENDING_HANDOFF",
  "submitted_at": "2026-11-11T10:30:00Z"
}
""")));

        AirlineDisruptionTicket ticket = createSampleTicket();

        TicketSubmissionReceipt receipt = adapter.finalizeTicket(ticket);

        assertThat(receipt).isNotNull();
        assertThat(receipt.ticketId()).isEqualTo(FIXED_TICKET_ID.toString());
        assertThat(receipt.pnr()).isEqualTo(new Pnr("A8F9X2"));
        assertThat(receipt.category()).isEqualTo(TicketCategory.HOTEL_ACCOMMODATION.name());
        assertThat(receipt.priority()).isEqualTo(TicketPriority.HIGH.name());
        assertThat(receipt.status()).isEqualTo("PENDING_HANDOFF");
        assertThat(receipt.submittedAt()).isEqualTo(Instant.parse("2026-11-11T10:30:00Z"));

        wireMockServer.verify(
                1,
                postRequestedFor(urlEqualTo("/tools/finalize_ticket"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.ticket_id", equalTo(FIXED_TICKET_ID.toString())))
                        .withRequestBody(matchingJsonPath("$.pnr", equalTo("A8F9X2")))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.category",
                                        equalTo(TicketCategory.HOTEL_ACCOMMODATION.name())))
                        .withRequestBody(
                                matchingJsonPath("$.priority", equalTo(TicketPriority.HIGH.name())))
                        .withRequestBody(
                                matchingJsonPath("$.extracted_entities.party_size", equalTo("2")))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.extracted_entities.requires_accessibility",
                                        equalTo("true")))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.extracted_entities.hotel_required", equalTo("true"))));
    }

    @Test
    @DisplayName("Executes outbound finalize_ticket HTTP call on a Java 21 Virtual Thread")
    void finalizeTicket_executesOnVirtualThread() {
        AtomicBoolean ranOnVirtualThread = new AtomicBoolean(false);
        try (ExecutorService trackingVirtualExecutor =
                Executors.newThreadPerTaskExecutor(
                        runnable ->
                                Thread.ofVirtual()
                                        .unstarted(
                                                () -> {
                                                    ranOnVirtualThread.set(
                                                            Thread.currentThread().isVirtual());
                                                    runnable.run();
                                                }))) {
            McpClientProperties properties =
                    new McpClientProperties(
                            "http://localhost:8091",
                            wireMockServer.baseUrl(),
                            "http://localhost:8093",
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2));
            McpTicketClientAdapter vtAdapter =
                    new McpTicketClientAdapter(
                            HttpClient.newHttpClient(),
                            trackingVirtualExecutor,
                            KafkaJsonCodecProvider.objectMapper(),
                            properties,
                            Mappers.getMapper(McpTicketMapper.class));

            wireMockServer.stubFor(
                    post(urlEqualTo("/tools/finalize_ticket"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
{
  "ticket_id": "99214000-78d1-47fa-bf87-9bc40efb8911",
  "pnr": "A8F9X2",
  "category": "HOTEL_ACCOMMODATION",
  "priority": "HIGH",
  "status": "PENDING_HANDOFF",
  "submitted_at": "2026-11-11T10:30:00Z"
}
""")));

            TicketSubmissionReceipt receipt = vtAdapter.finalizeTicket(createSampleTicket());
            assertThat(receipt.status()).isEqualTo("PENDING_HANDOFF");
            assertThat(ranOnVirtualThread.get()).isTrue();
        }
    }

    @Test
    @DisplayName("400 Bad Request throws McpClientException")
    void finalizeTicket_when400BadRequest_throwsMcpClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/finalize_ticket"))
                        .willReturn(
                                aResponse()
                                        .withStatus(400)
                                        .withBody("{\"error\":\"Invalid ticket payload\"}")));

        AirlineDisruptionTicket ticket = createSampleTicket();

        assertThatThrownBy(() -> adapter.finalizeTicket(ticket))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("400")
                .hasMessageContaining(FIXED_TICKET_ID.toString());
    }

    @Test
    @DisplayName("500 Internal Server Error throws McpClientException")
    void finalizeTicket_when500ServerError_throwsMcpClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/finalize_ticket"))
                        .willReturn(
                                aResponse()
                                        .withStatus(500)
                                        .withBody("{\"error\":\"Ticket store unavailable\"}")));

        AirlineDisruptionTicket ticket = createSampleTicket();

        assertThatThrownBy(() -> adapter.finalizeTicket(ticket))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("500")
                .hasMessageContaining(FIXED_TICKET_ID.toString());
    }

    @Test
    @DisplayName("Malformed JSON response throws McpClientException")
    void finalizeTicket_whenMalformedJson_throwsMcpClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/finalize_ticket"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{corrupted-json")));

        AirlineDisruptionTicket ticket = createSampleTicket();

        assertThatThrownBy(() -> adapter.finalizeTicket(ticket))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining(FIXED_TICKET_ID.toString());
    }

    @Test
    @DisplayName("Request timeout throws McpClientException")
    void finalizeTicket_whenTimeoutExceeded_throwsMcpClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/finalize_ticket"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withFixedDelay(1500)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{}")));

        AirlineDisruptionTicket ticket = createSampleTicket();

        assertThatThrownBy(() -> adapter.finalizeTicket(ticket))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining(FIXED_TICKET_ID.toString());
    }

    @Test
    @DisplayName("Null AirlineDisruptionTicket throws NullPointerException")
    void finalizeTicket_whenNullTicket_throwsNullPointerException() {
        assertThatThrownBy(() -> adapter.finalizeTicket(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ticket must not be null");
    }

    @Test
    @DisplayName("McpTicketClientAdapter supersedes NoOpDisruptionTicketPort fallback bean")
    void springContext_supersedesNoOpFallbackBean() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withBean(McpTicketMapper.class, () -> Mappers.getMapper(McpTicketMapper.class))
                .withUserConfiguration(
                        McpClientConfiguration.class,
                        NoOpDisruptionTicketPort.class,
                        McpTicketClientAdapter.class)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(McpTicketClientAdapter.class);
                            DisruptionTicketPort port = context.getBean(DisruptionTicketPort.class);
                            assertThat(port)
                                    .isInstanceOf(McpTicketClientAdapter.class)
                                    .isNotInstanceOf(NoOpDisruptionTicketPort.class);
                        });
    }

    private static AirlineDisruptionTicket createSampleTicket() {
        return Instancio.of(AirlineDisruptionTicket.class)
                .set(field(AirlineDisruptionTicket::ticketId), FIXED_TICKET_ID)
                .set(field(AirlineDisruptionTicket::passengerName), "Andres")
                .set(field(AirlineDisruptionTicket::pnr), "A8F9X2")
                .set(field(AirlineDisruptionTicket::originalFlightNumber), "SW-104")
                .set(
                        field(AirlineDisruptionTicket::disruptionReason),
                        DisruptionReason.CANCELED_WEATHER)
                .set(
                        field(AirlineDisruptionTicket::rebookingPreference),
                        RebookingPreference.NEXT_AVAILABLE)
                .set(field(AirlineDisruptionTicket::hotelRequired), true)
                .set(field(AirlineDisruptionTicket::specialAssistance), true)
                .set(field(AirlineDisruptionTicket::payload), Map.of("party_size", 2))
                .create();
    }
}
