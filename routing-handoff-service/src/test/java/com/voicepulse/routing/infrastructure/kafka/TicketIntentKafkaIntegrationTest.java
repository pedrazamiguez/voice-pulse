package com.voicepulse.routing.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.voicepulse.routing.RoutingHandoffApplication;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.inbound.ManageOperatorPresenceUseCase;
import com.voicepulse.routing.domain.ports.inbound.ProcessTicketIntentUseCase;
import com.voicepulse.routing.domain.ports.outbound.AudioRerouteSignalPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorDispatchPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
import com.voicepulse.routing.domain.service.OperatorAssignmentCoordinator;
import com.voicepulse.routing.infrastructure.kafka.consumer.TicketIntentKafkaConsumer;
import com.voicepulse.routing.infrastructure.kafka.producer.KafkaAudioRerouteSignalAdapter;
import com.voicepulse.routing.infrastructure.redis.RedisOperatorPresenceAdapter;
import com.voicepulse.routing.infrastructure.redis.RedisPriorityCallQueueAdapter;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.infrastructure.kafka.serializer.CallControlEnvelopeDeserializer;
import com.voicepulse.shared.infrastructure.kafka.serializer.TicketFormFilledEventSerializer;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.instancio.Instancio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end Kafka KRaft ({@code apache/kafka:3.8.0}) and Redis 7 ({@code redis:7-alpine})
 * Testcontainers integration test verifying {@link TicketIntentKafkaConsumer}, {@link
 * OperatorAssignmentCoordinator}, automatic queue-drain, DLQ handling, and {@link
 * KafkaAudioRerouteSignalAdapter}.
 */
@SpringBootTest(
        classes = RoutingHandoffApplication.class,
        properties = {
            "spring.main.web-application-type=none",
            "voicepulse.routing.persistence.enabled=false",
            "voicepulse.routing.redis.enabled=true",
            "voicepulse.routing.kafka.enabled=true",
            "voicepulse.routing.consumer-group=routing-handoff-kafka-it-group",
            "voicepulse.routing.operator-heartbeat-ttl=30s",
            "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration"
        })
@Testcontainers
@DirtiesContext
@DisplayName("Ticket Intent Kafka Consumer & Reroute Signal Integration Tests")
class TicketIntentKafkaIntegrationTest {

    private static final String TICKETS_INTENT_TOPIC = "telephony.tickets.intent";
    private static final String CALL_CONTROL_TOPIC = "telephony.calls.control";
    private static final String DLQ_TOPIC = "telephony.calls.dlq";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @MockitoBean private TicketPersistencePort ticketPersistencePort;
    @MockitoBean private OperatorDispatchPort operatorDispatchPort;

    private final ProcessTicketIntentUseCase processTicketIntentUseCase;
    private final ManageOperatorPresenceUseCase manageOperatorPresenceUseCase;
    private final AudioRerouteSignalPort audioRerouteSignalPort;
    private final OperatorPresencePort operatorPresencePort;
    private final PriorityCallQueuePort priorityCallQueuePort;
    private final TicketIntentKafkaConsumer ticketIntentKafkaConsumer;
    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final ReactiveStringRedisTemplate redisTemplate;

    private DefaultKafkaProducerFactory<String, TicketFormFilledEvent> ticketProducerFactory;
    private DefaultKafkaProducerFactory<String, byte[]> rawBytesProducerFactory;
    private KafkaTemplate<String, TicketFormFilledEvent> ticketTemplate;
    private KafkaTemplate<String, byte[]> rawBytesTemplate;
    private Consumer<String, CallControlEnvelope> controlConsumer;
    private Consumer<String, byte[]> dlqConsumer;

    TicketIntentKafkaIntegrationTest(
            @Autowired ProcessTicketIntentUseCase processTicketIntentUseCase,
            @Autowired ManageOperatorPresenceUseCase manageOperatorPresenceUseCase,
            @Autowired AudioRerouteSignalPort audioRerouteSignalPort,
            @Autowired OperatorPresencePort operatorPresencePort,
            @Autowired PriorityCallQueuePort priorityCallQueuePort,
            @Autowired TicketIntentKafkaConsumer ticketIntentKafkaConsumer,
            @Autowired KafkaListenerEndpointRegistry listenerRegistry,
            @Autowired ReactiveStringRedisTemplate redisTemplate) {
        this.processTicketIntentUseCase = processTicketIntentUseCase;
        this.manageOperatorPresenceUseCase = manageOperatorPresenceUseCase;
        this.audioRerouteSignalPort = audioRerouteSignalPort;
        this.operatorPresencePort = operatorPresencePort;
        this.priorityCallQueuePort = priorityCallQueuePort;
        this.ticketIntentKafkaConsumer = ticketIntentKafkaConsumer;
        this.listenerRegistry = listenerRegistry;
        this.redisTemplate = redisTemplate;
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @BeforeEach
    void setUp() {
        MessageListenerContainer container =
                listenerRegistry.getListenerContainer("routingTicketIntentListener");
        if (container != null) {
            ContainerTestUtils.waitForAssignment(container, 6);
        }

        redisTemplate
                .execute(
                        (ReactiveRedisConnection connection) ->
                                connection.serverCommands().flushDb())
                .blockLast(Duration.ofSeconds(5));
        reset(ticketPersistencePort, operatorDispatchPort);

        String bootstrapServers = KAFKA.getBootstrapServers();
        ticketProducerFactory =
                new DefaultKafkaProducerFactory<>(
                        Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
                        new StringSerializer(),
                        new TicketFormFilledEventSerializer());
        ticketTemplate = new KafkaTemplate<>(ticketProducerFactory);

        rawBytesProducerFactory =
                new DefaultKafkaProducerFactory<>(
                        Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
                        new StringSerializer(),
                        new ByteArraySerializer());
        rawBytesTemplate = new KafkaTemplate<>(rawBytesProducerFactory);

        controlConsumer = createControlConsumer(bootstrapServers);
        dlqConsumer = createDlqConsumer(bootstrapServers);
    }

    @AfterEach
    void tearDown() {
        if (controlConsumer != null) {
            controlConsumer.close();
        }
        if (dlqConsumer != null) {
            dlqConsumer.close();
        }
        if (ticketProducerFactory != null) {
            ticketProducerFactory.destroy();
        }
        if (rawBytesProducerFactory != null) {
            rawBytesProducerFactory.destroy();
        }
    }

    private static Consumer<String, CallControlEnvelope> createControlConsumer(
            String bootstrapServers) {
        String groupId = "it-control-verifier-" + UUID.randomUUID();
        Map<String, Object> props =
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        bootstrapServers,
                        ConsumerConfig.GROUP_ID_CONFIG,
                        groupId,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest",
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                        true);
        Consumer<String, CallControlEnvelope> consumer =
                new DefaultKafkaConsumerFactory<>(
                                props,
                                new StringDeserializer(),
                                new CallControlEnvelopeDeserializer())
                        .createConsumer();
        assignAllPartitions(consumer, CALL_CONTROL_TOPIC);
        return consumer;
    }

    private static Consumer<String, byte[]> createDlqConsumer(String bootstrapServers) {
        String groupId = "it-dlq-verifier-" + UUID.randomUUID();
        Map<String, Object> props =
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        bootstrapServers,
                        ConsumerConfig.GROUP_ID_CONFIG,
                        groupId,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest",
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                        true);
        Consumer<String, byte[]> consumer =
                new DefaultKafkaConsumerFactory<>(
                                props, new StringDeserializer(), new ByteArrayDeserializer())
                        .createConsumer();
        assignAllPartitions(consumer, DLQ_TOPIC);
        return consumer;
    }

    private static <V> void assignAllPartitions(Consumer<String, V> consumer, String topic) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(consumer.partitionsFor(topic)).isNotEmpty());
        List<TopicPartition> partitions =
                consumer.partitionsFor(topic).stream()
                        .map(info -> new TopicPartition(info.topic(), info.partition()))
                        .toList();
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
    }

    private static TicketFormFilledEvent sampleEvent(CallId callId, String targetSkillGroup) {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::callId), callId)
                .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(field(TicketFormFilledEvent::targetSkillGroup), targetSkillGroup)
                .set(field(TicketFormFilledEvent::hotelRequired), false)
                .set(field(TicketFormFilledEvent::specialAssistance), false)
                .set(
                        field(TicketFormFilledEvent::disruptionReason),
                        DisruptionReason.DELAYED_MAINTENANCE)
                .set(field(TicketFormFilledEvent::payload), Map.of())
                .create();
    }

    @Test
    @DisplayName("Should wire real Kafka, Redis, and Coordinator beans superseding fallbacks")
    void shouldWireRealAdaptersOverFallbacks() {
        assertThat(processTicketIntentUseCase).isInstanceOf(OperatorAssignmentCoordinator.class);
        assertThat(manageOperatorPresenceUseCase).isInstanceOf(OperatorAssignmentCoordinator.class);
        assertThat(audioRerouteSignalPort).isInstanceOf(KafkaAudioRerouteSignalAdapter.class);
        assertThat(operatorPresencePort).isInstanceOf(RedisOperatorPresenceAdapter.class);
        assertThat(priorityCallQueuePort).isInstanceOf(RedisPriorityCallQueueAdapter.class);
        assertThat(ticketIntentKafkaConsumer).isNotNull();
    }

    @Test
    @DisplayName(
            "Should consume TicketFormFilledEvent, claim operator, and emit OperatorRerouteSignal"
                    + " keyed by callId")
    void shouldConsumeTicketIntentAndEmitKeyedRerouteSignal() throws Exception {
        OperatorId operatorId = OperatorId.fromString("op-it-gold-1");
        manageOperatorPresenceUseCase.updatePresence(
                operatorId,
                "Gold Agent 1",
                OperatorAvailabilityStatus.AVAILABLE,
                Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                Instant.now());

        CallId callId = CallId.generate();
        TicketFormFilledEvent event = sampleEvent(callId, "DISRUPTION_DESK_GOLD");

        ticketTemplate.send(TICKETS_INTENT_TOPIC, callId.toString(), event).get();

        List<ConsumerRecord<String, CallControlEnvelope>> matchedControlRecords = new ArrayList<>();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            ConsumerRecords<String, CallControlEnvelope> polled =
                                    controlConsumer.poll(Duration.ofMillis(200));
                            for (ConsumerRecord<String, CallControlEnvelope> record : polled) {
                                if (callId.toString().equals(record.key())
                                        && record.value() instanceof OperatorRerouteSignal) {
                                    matchedControlRecords.add(record);
                                }
                            }
                            assertThat(matchedControlRecords).hasSize(1);
                        });

        ConsumerRecord<String, CallControlEnvelope> rerouteRecord =
                matchedControlRecords.getFirst();
        assertThat(rerouteRecord.key()).isEqualTo(callId.toString());
        OperatorRerouteSignal signal = (OperatorRerouteSignal) rerouteRecord.value();
        assertThat(signal.callId()).isEqualTo(callId);
        assertThat(signal.operatorId()).isEqualTo("op-it-gold-1");
        assertThat(signal.bridgeEndpoint())
                .isEqualTo("/ws/operators/op-it-gold-1/calls/" + callId + "/audio");

        verify(ticketPersistencePort)
                .saveTicket(
                        eq(event),
                        eq(SkillGroup.DISRUPTION_DESK_GOLD),
                        any(HandoffLifecycleState.Assigned.class));
        verify(operatorDispatchPort).pushAssignment(any(), eq(event));
    }

    @Test
    @DisplayName(
            "Should queue unmatched ticket and auto-drain with keyed OperatorRerouteSignal when"
                    + " operator becomes AVAILABLE")
    void shouldQueueBusyTicketAndDrainAutomaticallyWhenOperatorAvailable() throws Exception {
        CallId callId = CallId.generate();
        TicketFormFilledEvent event = sampleEvent(callId, "DISRUPTION_DESK_PRIORITY");

        ticketTemplate.send(TICKETS_INTENT_TOPIC, callId.toString(), event).get();

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                priorityCallQueuePort.queueDepth(
                                                        SkillGroup.DISRUPTION_DESK_PRIORITY))
                                        .isEqualTo(1L));

        OperatorId operatorId = OperatorId.fromString("op-it-prio-1");
        manageOperatorPresenceUseCase.updatePresence(
                operatorId,
                "Priority Agent 1",
                OperatorAvailabilityStatus.AVAILABLE,
                Set.of(SkillGroup.DISRUPTION_DESK_PRIORITY),
                Instant.now());

        List<ConsumerRecord<String, CallControlEnvelope>> matchedSignals = new ArrayList<>();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            ConsumerRecords<String, CallControlEnvelope> polled =
                                    controlConsumer.poll(Duration.ofMillis(200));
                            for (ConsumerRecord<String, CallControlEnvelope> record : polled) {
                                if (callId.toString().equals(record.key())
                                        && record.value() instanceof OperatorRerouteSignal) {
                                    matchedSignals.add(record);
                                }
                            }
                            assertThat(matchedSignals).hasSize(1);
                        });

        OperatorRerouteSignal drainedSignal =
                (OperatorRerouteSignal) matchedSignals.getFirst().value();
        assertThat(drainedSignal.callId()).isEqualTo(callId);
        assertThat(drainedSignal.operatorId()).isEqualTo("op-it-prio-1");
        assertThat(priorityCallQueuePort.queueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY)).isZero();
        verify(ticketPersistencePort, atLeastOnce())
                .updateTicketStatus(eq(event.ticketId()), eq("ASSIGNED"), any());
    }

    @Test
    @DisplayName(
            "Should route malformed ticket JSON to telephony.calls.dlq and keep consuming valid"
                    + " events")
    void shouldRouteMalformedPayloadToDlqWithoutHaltingPartition() throws Exception {
        String badCallKey = CallId.generate().toString();
        byte[] malformedJson = "{broken-ticket-json".getBytes(StandardCharsets.UTF_8);

        rawBytesTemplate.send(TICKETS_INTENT_TOPIC, badCallKey, malformedJson).get();

        List<ConsumerRecord<String, byte[]>> dlqRecords = new ArrayList<>();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            ConsumerRecords<String, byte[]> polled =
                                    dlqConsumer.poll(Duration.ofMillis(200));
                            for (ConsumerRecord<String, byte[]> record : polled) {
                                if (badCallKey.equals(record.key())) {
                                    dlqRecords.add(record);
                                }
                            }
                            assertThat(dlqRecords).hasSize(1);
                        });

        ConsumerRecord<String, byte[]> dlqRecord = dlqRecords.getFirst();
        assertThat(dlqRecord.value()).isEqualTo(malformedJson);
        Header originalTopicHeader = dlqRecord.headers().lastHeader("kafka_dlt-original-topic");
        assertThat(originalTopicHeader).isNotNull();
        assertThat(new String(originalTopicHeader.value(), StandardCharsets.UTF_8))
                .isEqualTo(TICKETS_INTENT_TOPIC);
        Header exceptionHeader = dlqRecord.headers().lastHeader("kafka_dlt-exception-fqcn");
        assertThat(exceptionHeader).isNotNull();

        OperatorId operatorId = OperatorId.fromString("op-it-std-1");
        manageOperatorPresenceUseCase.updatePresence(
                operatorId,
                "Standard Agent 1",
                OperatorAvailabilityStatus.AVAILABLE,
                Set.of(SkillGroup.DISRUPTION_DESK_STANDARD),
                Instant.now());

        CallId validCallId = CallId.generate();
        TicketFormFilledEvent validEvent = sampleEvent(validCallId, "DISRUPTION_DESK_STANDARD");
        ticketTemplate.send(TICKETS_INTENT_TOPIC, validCallId.toString(), validEvent).get();

        List<ConsumerRecord<String, CallControlEnvelope>> recoveredSignals = new ArrayList<>();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            ConsumerRecords<String, CallControlEnvelope> polled =
                                    controlConsumer.poll(Duration.ofMillis(200));
                            for (ConsumerRecord<String, CallControlEnvelope> record : polled) {
                                if (validCallId.toString().equals(record.key())
                                        && record.value() instanceof OperatorRerouteSignal) {
                                    recoveredSignals.add(record);
                                }
                            }
                            assertThat(recoveredSignals).hasSize(1);
                        });

        assertThat(((OperatorRerouteSignal) recoveredSignals.getFirst().value()).operatorId())
                .isEqualTo("op-it-std-1");
    }
}
