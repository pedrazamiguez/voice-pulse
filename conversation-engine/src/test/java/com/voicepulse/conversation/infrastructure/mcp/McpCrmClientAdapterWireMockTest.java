package com.voicepulse.conversation.infrastructure.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.conversation.domain.model.FrequentFlyerTier;
import com.voicepulse.conversation.domain.ports.outbound.CustomerLookupPort;
import com.voicepulse.conversation.infrastructure.fallback.NoOpCustomerLookupPort;
import com.voicepulse.conversation.infrastructure.mcp.config.McpClientConfiguration;
import com.voicepulse.conversation.infrastructure.mcp.config.McpClientProperties;
import com.voicepulse.conversation.infrastructure.mcp.mapper.McpCrmMapper;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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

@DisplayName("McpCrmClientAdapter WireMock Integration Tests")
class McpCrmClientAdapterWireMockTest {

    private WireMockServer wireMockServer;
    private ExecutorService virtualThreadExecutor;
    private McpCrmClientAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        McpClientProperties properties =
                new McpClientProperties(
                        wireMockServer.baseUrl(),
                        "http://localhost:8092",
                        "http://localhost:8093",
                        Duration.ofSeconds(1),
                        Duration.ofMillis(400));
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .executor(virtualThreadExecutor)
                        .build();
        McpCrmMapper mapper = Mappers.getMapper(McpCrmMapper.class);

        adapter =
                new McpCrmClientAdapter(
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
    @DisplayName("200 OK lookup deserializes and maps SkyWay Airlines passenger profile")
    void lookupCustomerByPnr_when200Ok_returnsMappedCustomerProfile() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/lookup_customer"))
                        .withHeader("Content-Type", equalTo("application/json"))
                        .withRequestBody(equalToJson("{\"pnr\":\"A8F9X2\"}"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "pnr": "A8F9X2",
                                                  "customer_name": "Andres",
                                                  "frequent_flyer_tier": "Gold",
                                                  "active_itinerary": {
                                                    "flight_number": "SW-104",
                                                    "origin": "CTU",
                                                    "destination": "MAD",
                                                    "status": "CANCELED_WEATHER",
                                                    "original_departure": "2026-11-01T01:45:00Z"
                                                  }
                                                }
                                                """)));

        Optional<CustomerProfile> result = adapter.lookupCustomerByPnr(new Pnr("A8F9X2"));

        assertThat(result).isPresent();
        CustomerProfile profile = result.orElseThrow();
        assertThat(profile.pnr()).isEqualTo(new Pnr("A8F9X2"));
        assertThat(profile.customerName()).isEqualTo("Andres");
        assertThat(profile.frequentFlyerTier()).isEqualTo(FrequentFlyerTier.GOLD);
        assertThat(profile.activeItinerary().flightNumber()).isEqualTo(new FlightNumber("SW-104"));
        assertThat(profile.activeItinerary().originAirport()).isEqualTo("CTU");
        assertThat(profile.activeItinerary().destinationAirport()).isEqualTo("MAD");
        assertThat(profile.activeItinerary().disruptionReason())
                .isEqualTo(DisruptionReason.CANCELED_WEATHER);
        assertThat(profile.activeItinerary().originalDeparture())
                .isEqualTo(Instant.parse("2026-11-01T01:45:00Z"));

        wireMockServer.verify(
                1,
                postRequestedFor(urlEqualTo("/tools/lookup_customer"))
                        .withRequestBody(equalToJson("{\"pnr\":\"A8F9X2\"}")));
    }

    @Test
    @DisplayName("200 OK response omitting pnr field populates Pnr from lookup parameter")
    void lookupCustomerByPnr_whenResponseOmitsPnrField_populatesRequestPnr() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/lookup_customer"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "customer_name": "Andres",
                                                  "frequent_flyer_tier": "Gold",
                                                  "active_itinerary": {
                                                    "flight_number": "SW-104",
                                                    "origin": "CTU",
                                                    "destination": "MAD",
                                                    "status": "CANCELLED_WEATHER",
                                                    "original_departure": "2026-11-01T01:45:00Z"
                                                  }
                                                }
                                                """)));

        Optional<CustomerProfile> result = adapter.lookupCustomerByPnr(new Pnr("A8F9X2"));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().pnr()).isEqualTo(new Pnr("A8F9X2"));
        assertThat(result.orElseThrow().activeItinerary().disruptionReason())
                .isEqualTo(DisruptionReason.CANCELED_WEATHER);
    }

    @Test
    @DisplayName("Executes outbound HTTP call on a Java 21 Virtual Thread")
    void lookupCustomerByPnr_executesOnVirtualThread() {
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
                            wireMockServer.baseUrl(),
                            "http://localhost:8092",
                            "http://localhost:8093",
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2));
            McpCrmClientAdapter vtAdapter =
                    new McpCrmClientAdapter(
                            HttpClient.newHttpClient(),
                            trackingVirtualExecutor,
                            KafkaJsonCodecProvider.objectMapper(),
                            properties,
                            Mappers.getMapper(McpCrmMapper.class));

            wireMockServer.stubFor(
                    post(urlEqualTo("/tools/lookup_customer"))
                            .willReturn(aResponse().withStatus(404)));

            Optional<CustomerProfile> result = vtAdapter.lookupCustomerByPnr(new Pnr("Z9Z9Z9"));
            assertThat(result).isEmpty();
            assertThat(ranOnVirtualThread.get()).isTrue();
        }
    }

    @Test
    @DisplayName("404 Not Found returns Optional.empty() without throwing")
    void lookupCustomerByPnr_when404NotFound_returnsEmptyOptional() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/lookup_customer"))
                        .withRequestBody(equalToJson("{\"pnr\":\"Z9Z9Z9\"}"))
                        .willReturn(
                                aResponse()
                                        .withStatus(404)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"error\":\"PNR not found\"}")));

        Optional<CustomerProfile> result = adapter.lookupCustomerByPnr(new Pnr("Z9Z9Z9"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("500 Internal Server Error throws McpClientException")
    void lookupCustomerByPnr_when500ServerError_throwsMcpClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/lookup_customer"))
                        .willReturn(
                                aResponse()
                                        .withStatus(500)
                                        .withBody("{\"error\":\"CRM database unavailable\"}")));

        assertThatThrownBy(() -> adapter.lookupCustomerByPnr(new Pnr("A8F9X2")))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("A8F9X2");
    }

    @Test
    @DisplayName("Malformed JSON payload throws McpClientException")
    void lookupCustomerByPnr_whenMalformedJson_throwsMcpClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/lookup_customer"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{not-valid-json")));

        assertThatThrownBy(() -> adapter.lookupCustomerByPnr(new Pnr("A8F9X2")))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("A8F9X2");
    }

    @Test
    @DisplayName("Request timeout throws McpClientException")
    void lookupCustomerByPnr_whenTimeoutExceeded_throwsMcpClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/tools/lookup_customer"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withFixedDelay(1500)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{}")));

        assertThatThrownBy(() -> adapter.lookupCustomerByPnr(new Pnr("A8F9X2")))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("A8F9X2");
    }

    @Test
    @DisplayName("Null Pnr throws NullPointerException")
    void lookupCustomerByPnr_whenNullPnr_throwsNullPointerException() {
        assertThatThrownBy(() -> adapter.lookupCustomerByPnr(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pnr must not be null");
    }

    @Test
    @DisplayName("McpCrmClientAdapter supersedes NoOpCustomerLookupPort fallback bean in Spring")
    void springContext_supersedesNoOpFallbackBean() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withBean(McpCrmMapper.class, () -> Mappers.getMapper(McpCrmMapper.class))
                .withUserConfiguration(
                        McpClientConfiguration.class,
                        NoOpCustomerLookupPort.class,
                        McpCrmClientAdapter.class)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(McpCrmClientAdapter.class);
                            CustomerLookupPort port = context.getBean(CustomerLookupPort.class);
                            assertThat(port)
                                    .isInstanceOf(McpCrmClientAdapter.class)
                                    .isNotInstanceOf(NoOpCustomerLookupPort.class);
                        });
    }
}
