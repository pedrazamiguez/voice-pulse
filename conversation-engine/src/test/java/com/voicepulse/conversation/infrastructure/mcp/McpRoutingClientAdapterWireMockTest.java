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

import com.github.tomakehurst.wiremock.WireMockServer;
import com.voicepulse.conversation.domain.model.OperatorHandoffReceipt;
import com.voicepulse.conversation.domain.model.OperatorQueueStatus;
import com.voicepulse.conversation.domain.ports.outbound.OperatorRoutingPort;
import com.voicepulse.conversation.infrastructure.fallback.NoOpOperatorRoutingPort;
import com.voicepulse.conversation.infrastructure.mcp.config.McpClientConfiguration;
import com.voicepulse.conversation.infrastructure.mcp.config.McpClientProperties;
import com.voicepulse.conversation.infrastructure.mcp.mapper.McpRoutingMapper;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import com.voicepulse.shared.model.CallId;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("McpRoutingClientAdapter WireMock Integration Tests")
class McpRoutingClientAdapterWireMockTest {

    private static final String GOLD_DEPARTMENT = "DISRUPTION_DESK_GOLD";
    private static final UUID FIXED_CALL_UUID =
            UUID.fromString("c9a4d812-78d1-47fa-bf87-9bc40efb8911");
    private static final UUID FIXED_TICKET_ID =
            UUID.fromString("99214000-78d1-47fa-bf87-9bc40efb8911");

    private WireMockServer wireMockServer;
    private ExecutorService virtualThreadExecutor;
    private McpRoutingClientAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        McpClientProperties properties =
                new McpClientProperties(
                        "http://localhost:8091",
                        "http://localhost:8092",
                        wireMockServer.baseUrl(),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(400));
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .executor(virtualThreadExecutor)
                        .build();
        McpRoutingMapper mapper = Mappers.getMapper(McpRoutingMapper.class);

        adapter =
                new McpRoutingClientAdapter(
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
    @DisplayName("200 OK check_queue_status sends department payload and maps OperatorQueueStatus")
    void checkQueueStatus_when200Ok_sendsPayloadAndReturnsMappedStatus() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/check_queue_status"))
                        .withHeader("Content-Type", equalTo("application/json"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
{
  "department": "DISRUPTION_DESK_GOLD",
  "available_operators": 6,
  "estimated_wait_seconds": 45,
  "queue_position": 3
}
""")));

        OperatorQueueStatus status = adapter.checkQueueStatus(GOLD_DEPARTMENT);

        assertThat(status).isNotNull();
        assertThat(status.department()).isEqualTo(GOLD_DEPARTMENT);
        assertThat(status.availableOperators()).isEqualTo(6);
        assertThat(status.estimatedWaitSeconds()).isEqualTo(45);
        assertThat(status.queuePosition()).isEqualTo(3);

        wireMockServer.verify(
                1,
                postRequestedFor(urlEqualTo("/tools/check_queue_status"))
                        .withRequestBody(
                                matchingJsonPath("$.department", equalTo(GOLD_DEPARTMENT))));
    }

    @Test
    @DisplayName(
            "200 OK transfer_to_human_operator sends RFC-0001 §4.4 payload and maps"
                    + " OperatorHandoffReceipt")
    void transferToHumanOperator_when200Ok_sendsPayloadAndReturnsMappedReceipt() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/transfer_to_human_operator"))
                        .withHeader("Content-Type", equalTo("application/json"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
{
  "call_id": "c9a4d812-78d1-47fa-bf87-9bc40efb8911",
  "ticket_id": "99214000-78d1-47fa-bf87-9bc40efb8911",
  "department": "DISRUPTION_DESK_GOLD",
  "status": "QUEUED",
  "estimated_wait_seconds": 120,
  "queue_position": 14,
  "dispatched_at": "2026-11-11T10:35:00Z"
}
""")));

        CallId callId = new CallId(FIXED_CALL_UUID);

        OperatorHandoffReceipt receipt =
                adapter.transferToHumanOperator(callId, GOLD_DEPARTMENT, FIXED_TICKET_ID);

        assertThat(receipt).isNotNull();
        assertThat(receipt.callId()).isEqualTo(callId);
        assertThat(receipt.ticketId()).isEqualTo(FIXED_TICKET_ID);
        assertThat(receipt.department()).isEqualTo(GOLD_DEPARTMENT);
        assertThat(receipt.status()).isEqualTo("QUEUED");
        assertThat(receipt.estimatedWaitSeconds()).isEqualTo(120);
        assertThat(receipt.queuePosition()).isEqualTo(14);
        assertThat(receipt.dispatchedAt()).isEqualTo(Instant.parse("2026-11-11T10:35:00Z"));

        wireMockServer.verify(
                1,
                postRequestedFor(urlEqualTo("/tools/transfer_to_human_operator"))
                        .withRequestBody(
                                matchingJsonPath("$.call_id", equalTo(FIXED_CALL_UUID.toString())))
                        .withRequestBody(matchingJsonPath("$.department", equalTo(GOLD_DEPARTMENT)))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.ticket_id", equalTo(FIXED_TICKET_ID.toString()))));
    }

    @Test
    @DisplayName(
            "200 OK minimal RFC-0003 §4.3 transfer response resolves call context fields"
                    + " cleanly")
    void transferToHumanOperator_whenMinimalRfcPayload_populatesFallbackContext() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/transfer_to_human_operator"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
{
  "status": "QUEUED",
  "estimated_wait_seconds": 120,
  "queue_position": 14
}
""")));

        CallId callId = new CallId(FIXED_CALL_UUID);

        OperatorHandoffReceipt receipt =
                adapter.transferToHumanOperator(callId, GOLD_DEPARTMENT, FIXED_TICKET_ID);

        assertThat(receipt.callId()).isEqualTo(callId);
        assertThat(receipt.ticketId()).isEqualTo(FIXED_TICKET_ID);
        assertThat(receipt.department()).isEqualTo(GOLD_DEPARTMENT);
        assertThat(receipt.status()).isEqualTo("QUEUED");
        assertThat(receipt.estimatedWaitSeconds()).isEqualTo(120);
        assertThat(receipt.queuePosition()).isEqualTo(14);
        assertThat(receipt.dispatchedAt()).isNotNull();
    }

    @Test
    @DisplayName("Executes outbound routing HTTP calls on Java 21 Virtual Threads")
    void routingCalls_executeOnVirtualThread() {
        AtomicBoolean queueRanOnVirtualThread = new AtomicBoolean(false);
        AtomicBoolean transferRanOnVirtualThread = new AtomicBoolean(false);

        try (ExecutorService trackingVirtualExecutor =
                Executors.newThreadPerTaskExecutor(
                        runnable ->
                                Thread.ofVirtual()
                                        .unstarted(
                                                () -> {
                                                    boolean isVirtual =
                                                            Thread.currentThread().isVirtual();
                                                    if (!queueRanOnVirtualThread.get()) {
                                                        queueRanOnVirtualThread.set(isVirtual);
                                                    } else {
                                                        transferRanOnVirtualThread.set(isVirtual);
                                                    }
                                                    runnable.run();
                                                }))) {
            McpClientProperties properties =
                    new McpClientProperties(
                            "http://localhost:8091",
                            "http://localhost:8092",
                            wireMockServer.baseUrl(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2));
            McpRoutingClientAdapter vtAdapter =
                    new McpRoutingClientAdapter(
                            HttpClient.newHttpClient(),
                            trackingVirtualExecutor,
                            KafkaJsonCodecProvider.objectMapper(),
                            properties,
                            Mappers.getMapper(McpRoutingMapper.class));

            wireMockServer.stubFor(
                    post(urlEqualTo("/tools/check_queue_status"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
{
  "department": "DISRUPTION_DESK_GOLD",
  "available_operators": 4,
  "estimated_wait_seconds": 30,
  "queue_position": 2
}
""")));
            wireMockServer.stubFor(
                    post(urlEqualTo("/tools/transfer_to_human_operator"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
{
  "status": "QUEUED",
  "estimated_wait_seconds": 30,
  "queue_position": 2
}
""")));

            OperatorQueueStatus status = vtAdapter.checkQueueStatus(GOLD_DEPARTMENT);
            OperatorHandoffReceipt receipt =
                    vtAdapter.transferToHumanOperator(
                            new CallId(FIXED_CALL_UUID), GOLD_DEPARTMENT, FIXED_TICKET_ID);

            assertThat(status.availableOperators()).isEqualTo(4);
            assertThat(receipt.status()).isEqualTo("QUEUED");
            assertThat(queueRanOnVirtualThread.get()).isTrue();
            assertThat(transferRanOnVirtualThread.get()).isTrue();
        }
    }

    @Test
    @DisplayName("400 and 404 HTTP errors throw McpClientException")
    void routingCalls_when4xx_throwMcpClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/check_queue_status"))
                        .willReturn(
                                aResponse()
                                        .withStatus(404)
                                        .withBody("{\"error\":\"Unknown department\"}")));
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/transfer_to_human_operator"))
                        .willReturn(
                                aResponse()
                                        .withStatus(400)
                                        .withBody("{\"error\":\"Invalid transfer request\"}")));

        assertThatThrownBy(() -> adapter.checkQueueStatus("UNKNOWN_DESK"))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("UNKNOWN_DESK");

        CallId callId = new CallId(FIXED_CALL_UUID);
        assertThatThrownBy(
                        () ->
                                adapter.transferToHumanOperator(
                                        callId, GOLD_DEPARTMENT, FIXED_TICKET_ID))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("400")
                .hasMessageContaining(FIXED_TICKET_ID.toString());
    }

    @Test
    @DisplayName("500 Internal Server Error throws McpClientException")
    void routingCalls_when500ServerError_throwMcpClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/check_queue_status"))
                        .willReturn(
                                aResponse()
                                        .withStatus(500)
                                        .withBody("{\"error\":\"Routing service unavailable\"}")));
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/transfer_to_human_operator"))
                        .willReturn(
                                aResponse()
                                        .withStatus(500)
                                        .withBody("{\"error\":\"SIP bridge unavailable\"}")));

        assertThatThrownBy(() -> adapter.checkQueueStatus(GOLD_DEPARTMENT))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("500")
                .hasMessageContaining(GOLD_DEPARTMENT);

        CallId callId = new CallId(FIXED_CALL_UUID);
        assertThatThrownBy(
                        () ->
                                adapter.transferToHumanOperator(
                                        callId, GOLD_DEPARTMENT, FIXED_TICKET_ID))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("500")
                .hasMessageContaining(FIXED_CALL_UUID.toString());
    }

    @Test
    @DisplayName("Malformed JSON response throws McpClientException")
    void routingCalls_whenMalformedJson_throwMcpClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/check_queue_status"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{broken-json")));
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/transfer_to_human_operator"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{broken-json")));

        assertThatThrownBy(() -> adapter.checkQueueStatus(GOLD_DEPARTMENT))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining(GOLD_DEPARTMENT);

        CallId callId = new CallId(FIXED_CALL_UUID);
        assertThatThrownBy(
                        () ->
                                adapter.transferToHumanOperator(
                                        callId, GOLD_DEPARTMENT, FIXED_TICKET_ID))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining(FIXED_TICKET_ID.toString());
    }

    @Test
    @DisplayName("Request timeout throws McpClientException")
    void routingCalls_whenTimeoutExceeded_throwMcpClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/check_queue_status"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withFixedDelay(1500)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{}")));
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/transfer_to_human_operator"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withFixedDelay(1500)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{}")));

        assertThatThrownBy(() -> adapter.checkQueueStatus(GOLD_DEPARTMENT))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining(GOLD_DEPARTMENT);

        CallId callId = new CallId(FIXED_CALL_UUID);
        assertThatThrownBy(
                        () ->
                                adapter.transferToHumanOperator(
                                        callId, GOLD_DEPARTMENT, FIXED_TICKET_ID))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining(FIXED_TICKET_ID.toString());
    }

    @Test
    @DisplayName("Null or blank arguments fail fast")
    void routingCalls_whenNullOrBlank_failFast() {
        CallId callId = new CallId(FIXED_CALL_UUID);

        assertThatThrownBy(() -> adapter.checkQueueStatus(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("department must not be null");
        assertThatThrownBy(() -> adapter.checkQueueStatus("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("department must not be blank");

        assertThatThrownBy(
                        () ->
                                adapter.transferToHumanOperator(
                                        null, GOLD_DEPARTMENT, FIXED_TICKET_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId must not be null");
        assertThatThrownBy(() -> adapter.transferToHumanOperator(callId, null, FIXED_TICKET_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("department must not be null");
        assertThatThrownBy(() -> adapter.transferToHumanOperator(callId, " ", FIXED_TICKET_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("department must not be blank");
        assertThatThrownBy(() -> adapter.transferToHumanOperator(callId, GOLD_DEPARTMENT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ticketId must not be null");
    }

    @Test
    @DisplayName("McpRoutingClientAdapter supersedes NoOpOperatorRoutingPort fallback bean")
    void springContext_supersedesNoOpFallbackBean() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withBean(McpRoutingMapper.class, () -> Mappers.getMapper(McpRoutingMapper.class))
                .withUserConfiguration(
                        McpClientConfiguration.class,
                        NoOpOperatorRoutingPort.class,
                        McpRoutingClientAdapter.class)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(McpRoutingClientAdapter.class);
                            OperatorRoutingPort port = context.getBean(OperatorRoutingPort.class);
                            assertThat(port)
                                    .isInstanceOf(McpRoutingClientAdapter.class)
                                    .isNotInstanceOf(NoOpOperatorRoutingPort.class);
                        });
    }
}
