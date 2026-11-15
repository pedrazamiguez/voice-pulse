package com.voicepulse.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.gateway.EdgeGatewayApplication;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.gateway.infrastructure.ws.CallBridgeRegistry;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorPresenceDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorPresenceRequestDto;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.infrastructure.kafka.serializer.TicketFormFilledEventSerializer;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.CallSessionId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Testcontainers
@DisplayName("Multi-Module End-to-End Operator Handoff & Audio Bridge Testcontainers Suite")
class EndToEndOperatorHandoffIntegrationTest {

    private static final String TOPIC_CALLS_RAW = "telephony.calls.raw";
    private static final String TOPIC_CALLS_CONTROL = "telephony.calls.control";
    private static final String TOPIC_TICKETS_INTENT = "telephony.tickets.intent";
    private static final String OPERATOR_ID = "op-e2e-gold";
    private static final int PCM_FRAME_BYTES = 640;

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("voicepulse")
                    .withUsername("voicepulse")
                    .withPassword("voicepulse")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(resolveInitDbSqlPath()),
                            "/docker-entrypoint-initdb.d/init-db.sql");

    private static ConfigurableApplicationContext gatewayContext;
    private static ConfigurableApplicationContext routingContext;
    private static int gatewayPort;
    private static int routingPort;
    private static DefaultKafkaProducerFactory<String, TicketFormFilledEvent> ticketProducerFactory;
    private static KafkaTemplate<String, TicketFormFilledEvent> ticketTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebSocketClient webSocketClient = new ReactorNettyWebSocketClient();

    @BeforeAll
    static void bootFullMicroserviceMesh() throws Exception {
        createTopics();

        gatewayContext =
                new SpringApplicationBuilder(EdgeGatewayApplication.class)
                        .web(WebApplicationType.REACTIVE)
                        .run(
                                "--server.port=0",
                                "--spring.application.name=edge-gateway-e2e",
                                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                                "--spring.autoconfigure.exclude="
                                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                                    + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration,"
                                    + "org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration,"
                                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                                    + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                                "--voicepulse.gateway.audio-publisher=kafka",
                                "--voicepulse.gateway.bridge.outbound-buffer-capacity=8192",
                                "--voicepulse.gateway.kafka.topic=" + TOPIC_CALLS_RAW,
                                "--voicepulse.gateway.kafka.control-topic=" + TOPIC_CALLS_CONTROL,
                                "--voicepulse.gateway.kafka.consumer-group=edge-gateway-e2e-group",
                                "--voicepulse.gateway.kafka.send-timeout=5s",
                                "--voicepulse.gateway.kafka.buffer-memory=33554432",
                                "--voicepulse.gateway.kafka.max-block-timeout=5s");

        gatewayPort =
                ((ReactiveWebServerApplicationContext) gatewayContext).getWebServer().getPort();

        routingContext =
                new SpringApplicationBuilder(RoutingHandoffApplication.class)
                        .web(WebApplicationType.REACTIVE)
                        .run(
                                "--server.port=0",
                                "--spring.application.name=routing-handoff-e2e",
                                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                                "--spring.data.redis.host=" + REDIS.getHost(),
                                "--spring.data.redis.port=" + REDIS.getFirstMappedPort(),
                                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                                "--spring.datasource.username=" + POSTGRES.getUsername(),
                                "--spring.datasource.password=" + POSTGRES.getPassword(),
                                "--spring.datasource.driver-class-name="
                                        + POSTGRES.getDriverClassName(),
                                "--voicepulse.routing.persistence.enabled=true",
                                "--voicepulse.routing.redis.enabled=true",
                                "--voicepulse.routing.kafka.enabled=true",
                                "--voicepulse.routing.kafka.concurrency=1",
                                "--voicepulse.routing.kafka.auto-offset-reset=earliest",
                                "--voicepulse.routing.kafka.topics.ticket-intent.partitions=1",
                                "--voicepulse.routing.kafka.topics.call-control.partitions=1",
                                "--voicepulse.routing.kafka.topics.dlq.partitions=1",
                                "--voicepulse.routing.console.gateway-ws-base-url=ws://localhost:"
                                        + gatewayPort);

        routingPort =
                ((ReactiveWebServerApplicationContext) routingContext).getWebServer().getPort();

        waitForKafkaListeners(gatewayContext);
        waitForKafkaListeners(routingContext);

        ticketProducerFactory =
                new DefaultKafkaProducerFactory<>(
                        Map.of(
                                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers()),
                        new StringSerializer(),
                        new TicketFormFilledEventSerializer());
        ticketTemplate = new KafkaTemplate<>(ticketProducerFactory);
    }

    @AfterAll
    static void tearDownMesh() {
        if (ticketProducerFactory != null) {
            ticketProducerFactory.destroy();
        }
        if (routingContext != null) {
            routingContext.close();
        }
        if (gatewayContext != null) {
            gatewayContext.close();
        }
    }

    @Test
    @DisplayName(
            "executes full call handoff across Kafka KRaft, Redis, Postgres, Routing Console, and"
                    + " bidirectional Gateway PCM audio bridge with zero Netty leaks")
    void executesFullMultiModuleOperatorHandoffEndToEnd() throws Exception {
        CallId callId = CallId.generate();
        CallSessionId sessionId = CallSessionId.fromCallId(callId);
        UUID ticketId = UUID.randomUUID();

        // 1. Register operator presence as AVAILABLE in Redis via Routing HTTP API
        WebTestClient routingClient =
                WebTestClient.bindToServer().baseUrl("http://localhost:" + routingPort).build();
        routingClient
                .put()
                .uri("/api/operators/{operatorId}/presence", OPERATOR_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        new OperatorPresenceRequestDto(
                                "Elena Vance", "AVAILABLE", Set.of("DISRUPTION_DESK_GOLD")))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(OperatorPresenceDto.class)
                .value(dto -> assertThat(dto.status()).isEqualTo("AVAILABLE"));

        // 2. Connect Operator Console WebSocket to receive assignment push
        Sinks.One<Void> consoleStop = Sinks.one();
        List<JsonNode> consoleFrames = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> consoleWsDone =
                webSocketClient
                        .execute(
                                URI.create(
                                        "ws://localhost:"
                                                + routingPort
                                                + "/ws/operators/"
                                                + OPERATOR_ID),
                                session -> {
                                    Mono<Void> receive =
                                            session.receive()
                                                    .doOnNext(
                                                            msg -> {
                                                                if (msg.getType()
                                                                        == WebSocketMessage.Type
                                                                                .TEXT) {
                                                                    try {
                                                                        consoleFrames.add(
                                                                                objectMapper
                                                                                        .readTree(
                                                                                                msg
                                                                                                        .getPayloadAsText()));
                                                                    } catch (Exception ex) {
                                                                        throw new IllegalStateException(
                                                                                ex);
                                                                    }
                                                                }
                                                            })
                                                    .then();
                                    return Mono.when(
                                            consoleStop.asMono().then(session.close()), receive);
                                })
                        .toFuture();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(consoleFrames)
                                        .anyMatch(
                                                f ->
                                                        "CONSOLE_SNAPSHOT"
                                                                .equals(f.path("type").asText())));

        // 3. Connect Caller Audio WebSocket to Edge Gateway and send initial AI-bound PCM frame
        Sinks.Many<byte[]> callerOutbound = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.One<Void> callerStop = Sinks.one();
        List<String> callerControlMsgs = new CopyOnWriteArrayList<>();
        List<byte[]> callerReceivedOperatorAudio = new CopyOnWriteArrayList<>();

        CompletableFuture<Void> callerWsDone =
                webSocketClient
                        .execute(
                                URI.create(
                                        "ws://localhost:"
                                                + gatewayPort
                                                + "/ws/calls/"
                                                + callId
                                                + "/audio"),
                                session -> {
                                    Mono<Void> send =
                                            session.send(
                                                            callerOutbound
                                                                    .asFlux()
                                                                    .takeUntilOther(
                                                                            callerStop.asMono())
                                                                    .map(
                                                                            bytes ->
                                                                                    session
                                                                                            .binaryMessage(
                                                                                                    f ->
                                                                                                            f
                                                                                                                    .wrap(
                                                                                                                            bytes))))
                                                    .then(session.close());
                                    Mono<Void> receive =
                                            session.receive()
                                                    .doOnNext(
                                                            msg -> {
                                                                if (msg.getType()
                                                                        == WebSocketMessage.Type
                                                                                .TEXT) {
                                                                    callerControlMsgs.add(
                                                                            msg.getPayloadAsText());
                                                                } else if (msg.getType()
                                                                        == WebSocketMessage.Type
                                                                                .BINARY) {
                                                                    DataBuffer buf =
                                                                            msg.getPayload();
                                                                    byte[] bytes =
                                                                            new byte
                                                                                    [buf
                                                                                            .readableByteCount()];
                                                                    buf.read(bytes);
                                                                    callerReceivedOperatorAudio.add(
                                                                            bytes);
                                                                }
                                                            })
                                                    .then();
                                    return Mono.when(send, receive);
                                })
                        .toFuture();

        SessionRegistryPort gatewaySessionRegistry =
                gatewayContext.getBean(SessionRegistryPort.class);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(gatewaySessionRegistry.findById(sessionId)).isPresent());

        try (KafkaConsumer<String, byte[]> rawConsumer = createRawAudioConsumer()) {
            rawConsumer.subscribe(Collections.singletonList(TOPIC_CALLS_RAW));
            callerOutbound.tryEmitNext(encodeFrame(1, 0));

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> {
                                ConsumerRecords<String, byte[]> polled =
                                        rawConsumer.poll(Duration.ofMillis(200));
                                assertThat(polled.count()).isGreaterThanOrEqualTo(1);
                            });

            // 4. Emit TicketFormFilledEvent onto telephony.tickets.intent partitioned by callId
            TicketFormFilledEvent ticketEvent =
                    new TicketFormFilledEvent(
                            callId,
                            ticketId,
                            "Maria Alvarez",
                            new Pnr("K9M2P4"),
                            new FlightNumber("SW-404"),
                            DisruptionReason.CANCELED_WEATHER,
                            RebookingPreference.NEXT_AVAILABLE,
                            true,
                            true,
                            "DISRUPTION_DESK_GOLD",
                            Map.of("origin", "MAD", "destination", "FRA"),
                            Instant.now());
            ticketTemplate
                    .send(TOPIC_TICKETS_INTENT, callId.toString(), ticketEvent)
                    .get(5, TimeUnit.SECONDS);

            // 5. Verify Postgres persistence, Console ASSIGNMENT push, and Gateway INTERRUPT +
            // Bridge
            JdbcTemplate jdbcTemplate = routingContext.getBean(JdbcTemplate.class);
            CallBridgeRegistry bridgeRegistry = gatewayContext.getBean(CallBridgeRegistry.class);

            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(
                            () -> {
                                Integer ticketRows =
                                        jdbcTemplate.queryForObject(
                                                "SELECT COUNT(*) FROM disruption_tickets WHERE"
                                                        + " call_id = ?",
                                                Integer.class,
                                                callId.value());
                                assertThat(ticketRows).isEqualTo(1);

                                Integer handoffRows =
                                        jdbcTemplate.queryForObject(
                                                "SELECT COUNT(*) FROM operator_handoff_logs WHERE"
                                                        + " call_id = ? AND operator_id = ?",
                                                Integer.class,
                                                callId.value(),
                                                OPERATOR_ID);
                                assertThat(handoffRows).isGreaterThanOrEqualTo(1);

                                assertThat(bridgeRegistry.getAssignedOperatorId(sessionId))
                                        .contains(OPERATOR_ID);
                                assertThat(callerControlMsgs)
                                        .anyMatch(msg -> msg.contains("\"op\":\"INTERRUPT\""));
                                assertThat(consoleFrames)
                                        .anyMatch(
                                                f -> "ASSIGNMENT".equals(f.path("type").asText()));
                            });

            JsonNode assignmentMsg =
                    consoleFrames.stream()
                            .filter(f -> "ASSIGNMENT".equals(f.path("type").asText()))
                            .findFirst()
                            .orElseThrow();
            String bridgeUrl = assignmentMsg.path("assignment").path("bridgeUrl").asText();
            assertThat(bridgeUrl)
                    .isEqualTo(
                            "ws://localhost:"
                                    + gatewayPort
                                    + "/ws/operators/"
                                    + OPERATOR_ID
                                    + "/calls/"
                                    + callId
                                    + "/audio");

            // 6. Operator connects directly to bridgeUrl and exchanges bidirectional PCM frames
            Sinks.Many<byte[]> operatorOutbound = Sinks.many().unicast().onBackpressureBuffer();
            Sinks.One<Void> operatorStop = Sinks.one();
            List<byte[]> operatorReceivedCallerAudio = new CopyOnWriteArrayList<>();

            CompletableFuture<Void> operatorAudioWsDone =
                    webSocketClient
                            .execute(
                                    URI.create(bridgeUrl),
                                    session -> {
                                        Mono<Void> send =
                                                session.send(
                                                                operatorOutbound
                                                                        .asFlux()
                                                                        .takeUntilOther(
                                                                                operatorStop
                                                                                        .asMono())
                                                                        .map(
                                                                                bytes ->
                                                                                        session
                                                                                                .binaryMessage(
                                                                                                        f ->
                                                                                                                f
                                                                                                                        .wrap(
                                                                                                                                bytes))))
                                                        .then(session.close());
                                        Mono<Void> receive =
                                                session.receive()
                                                        .filter(
                                                                msg ->
                                                                        msg.getType()
                                                                                == WebSocketMessage
                                                                                        .Type
                                                                                        .BINARY)
                                                        .doOnNext(
                                                                msg -> {
                                                                    DataBuffer buf =
                                                                            msg.getPayload();
                                                                    byte[] bytes =
                                                                            new byte
                                                                                    [buf
                                                                                            .readableByteCount()];
                                                                    buf.read(bytes);
                                                                    operatorReceivedCallerAudio.add(
                                                                            bytes);
                                                                })
                                                        .then()
                                                        .doFinally(
                                                                sig -> operatorStop.tryEmitEmpty());
                                        return Mono.when(send, receive);
                                    })
                            .toFuture();

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () ->
                                    assertThat(bridgeRegistry.getConnectedSession(sessionId))
                                            .isPresent());

            int bridgeFrames = 15;
            for (int i = 0; i < bridgeFrames; i++) {
                callerOutbound.tryEmitNext(encodeFrame(1, i + 1));
                operatorOutbound.tryEmitNext(encodeFrame(2, i + 1));
            }

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> {
                                assertThat(operatorReceivedCallerAudio).hasSize(bridgeFrames);
                                assertThat(callerReceivedOperatorAudio).hasSize(bridgeFrames);
                            });

            for (int i = 0; i < bridgeFrames; i++) {
                assertThat(ByteBuffer.wrap(operatorReceivedCallerAudio.get(i)).getInt(8))
                        .isEqualTo(i + 1);
                assertThat(ByteBuffer.wrap(callerReceivedOperatorAudio.get(i)).getInt(8))
                        .isEqualTo(i + 1);
            }

            // Confirm no post-bridge caller audio leaked onto telephony.calls.raw
            ConsumerRecords<String, byte[]> postBridgeKafka =
                    rawConsumer.poll(Duration.ofMillis(400));
            assertThat(postBridgeKafka.count()).isZero();

            // 7. Caller disconnect closes bridge and tears down operator audio WS cleanly
            callerStop.tryEmitEmpty();
            callerWsDone.get(5, TimeUnit.SECONDS);
            operatorAudioWsDone.get(5, TimeUnit.SECONDS);

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () ->
                                    assertThat(bridgeRegistry.getAssignedOperatorId(sessionId))
                                            .isEmpty());

            consoleStop.tryEmitEmpty();
            consoleWsDone.get(5, TimeUnit.SECONDS);
        }
    }

    private static void createTopics() throws Exception {
        try (AdminClient admin =
                AdminClient.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers()))) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(TOPIC_CALLS_RAW, 1, (short) 1),
                                    new NewTopic(TOPIC_CALLS_CONTROL, 1, (short) 1),
                                    new NewTopic(TOPIC_TICKETS_INTENT, 1, (short) 1)))
                    .all()
                    .get(15, TimeUnit.SECONDS);
        }
    }

    private static void waitForKafkaListeners(ConfigurableApplicationContext ctx) {
        KafkaListenerEndpointRegistry registry = ctx.getBean(KafkaListenerEndpointRegistry.class);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(registry.getListenerContainers())
                                        .isNotEmpty()
                                        .allSatisfy(
                                                container -> {
                                                    assertThat(container.isRunning()).isTrue();
                                                    assertThat(container.getAssignedPartitions())
                                                            .isNotEmpty();
                                                }));
    }

    private static KafkaConsumer<String, byte[]> createRawAudioConsumer() {
        return new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        KAFKA.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "e2e-raw-audio-verifier-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest"),
                new StringDeserializer(),
                new ByteArrayDeserializer());
    }

    private static Path resolveInitDbSqlPath() {
        Path direct = Path.of("config/postgres/init-db.sql");
        if (Files.exists(direct)) {
            return direct;
        }
        Path parent = Path.of("../config/postgres/init-db.sql");
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalStateException("Could not locate config/postgres/init-db.sql");
    }

    private static byte[] encodeFrame(int tag, int seq) {
        byte[] bytes = new byte[PCM_FRAME_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.putInt(0, tag);
        buf.putInt(8, seq);
        return bytes;
    }
}
