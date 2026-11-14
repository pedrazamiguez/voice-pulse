package com.voicepulse.routing.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.instancio.Select.field;

import com.voicepulse.routing.RoutingHandoffApplication;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

/**
 * Redis 7 Testcontainers integration test verifying {@link RedisOperatorPresenceAdapter} and {@link
 * RedisPriorityCallQueueAdapter} against {@code redis:7-alpine} (RFC-0001 §3 & §4.5, Epic #140
 * Phase 3).
 */
@SpringBootTest(
        classes = RoutingHandoffApplication.class,
        properties = {
            "spring.main.web-application-type=none",
            "voicepulse.routing.persistence.enabled=false",
            "voicepulse.routing.redis.enabled=true",
            "voicepulse.routing.kafka.enabled=false",
            "voicepulse.routing.operator-heartbeat-ttl=1200ms",
            "voicepulse.routing.max-queue-capacity=5",
            "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration"
        })
@Testcontainers
@DirtiesContext
@DisplayName("Redis 7 Operator Presence & Priority Queue Integration Tests")
class RedisPresenceAndQueueIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-11-14T13:00:00Z");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private final OperatorPresencePort presencePort;
    private final PriorityCallQueuePort queuePort;
    private final RedisOperatorPresenceAdapter presenceAdapter;
    private final RedisPriorityCallQueueAdapter queueAdapter;
    private final ReactiveStringRedisTemplate redisTemplate;

    RedisPresenceAndQueueIntegrationTest(
            @Autowired OperatorPresencePort presencePort,
            @Autowired PriorityCallQueuePort queuePort,
            @Autowired RedisOperatorPresenceAdapter presenceAdapter,
            @Autowired RedisPriorityCallQueueAdapter queueAdapter,
            @Autowired ReactiveStringRedisTemplate redisTemplate) {
        this.presencePort = presencePort;
        this.queuePort = queuePort;
        this.presenceAdapter = presenceAdapter;
        this.queueAdapter = queueAdapter;
        this.redisTemplate = redisTemplate;
    }

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @BeforeEach
    void flushRedis() {
        redisTemplate
                .execute(
                        (ReactiveRedisConnection connection) ->
                                connection.serverCommands().flushDb())
                .blockLast(Duration.ofSeconds(5));
    }

    private static QueuedTicketItem sampleQueueItem(
            SkillGroup skillGroup, int priorityScore, Instant enqueuedAt) {
        TicketFormFilledEvent event =
                Instancio.of(TicketFormFilledEvent.class)
                        .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                        .set(
                                field(TicketFormFilledEvent::originalFlightNumber),
                                new FlightNumber("SW-104"))
                        .set(field(TicketFormFilledEvent::targetSkillGroup), skillGroup.name())
                        .set(
                                field(TicketFormFilledEvent::payload),
                                Map.of("hotel_rooms", 1, "priority_tier", skillGroup.name()))
                        .set(field(TicketFormFilledEvent::timestamp), BASE_TIME)
                        .create();
        return new QueuedTicketItem(event, skillGroup, priorityScore, enqueuedAt);
    }

    @Test
    @DisplayName("Should wire Redis adapters over NoOp fallback beans")
    void shouldWireRedisAdaptersOverFallbackBeans() {
        assertThat(presencePort).isInstanceOf(RedisOperatorPresenceAdapter.class);
        assertThat(queuePort).isInstanceOf(RedisPriorityCallQueueAdapter.class);
    }

    @Test
    @DisplayName("Should manage AVAILABLE, BUSY, PAUSED, and OFFLINE operator states across skills")
    void shouldManageOperatorStatesAcrossSkillGroups() {
        OperatorId operatorId = OperatorId.fromString("op-state-1");
        OperatorPresence available =
                new OperatorPresence(
                        operatorId,
                        "Andres Pedraza",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(
                                SkillGroup.DISRUPTION_DESK_GOLD,
                                SkillGroup.DISRUPTION_DESK_PRIORITY),
                        Optional.empty(),
                        BASE_TIME);

        presencePort.savePresence(available);

        assertThat(presencePort.findPresence(operatorId)).contains(available);
        assertThat(presencePort.findAvailableBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .containsExactly(available);
        assertThat(presencePort.findAvailableBySkillGroup(SkillGroup.DISRUPTION_DESK_PRIORITY))
                .containsExactly(available);
        assertThat(presencePort.findAvailableBySkillGroup(SkillGroup.DISRUPTION_DESK_STANDARD))
                .isEmpty();

        CallId callId = CallId.generate();
        Instant claimedAt = BASE_TIME.plusSeconds(2);
        assertThat(presencePort.tryClaimOperator(operatorId, callId, claimedAt)).isTrue();
        assertThat(presencePort.tryClaimOperator(operatorId, CallId.generate(), claimedAt))
                .isFalse();

        Optional<OperatorPresence> busy = presencePort.findPresence(operatorId);
        assertThat(busy).isPresent();
        assertThat(busy.get().status()).isEqualTo(OperatorAvailabilityStatus.BUSY);
        assertThat(busy.get().activeCallId()).contains(callId);
        assertThat(presencePort.findAvailableBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .isEmpty();
        assertThat(presencePort.findBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .containsExactly(busy.get());

        Instant pausedAt = claimedAt.plusSeconds(3);
        presencePort.releaseOperator(operatorId, OperatorAvailabilityStatus.PAUSED, pausedAt);
        Optional<OperatorPresence> paused = presencePort.findPresence(operatorId);
        assertThat(paused).isPresent();
        assertThat(paused.get().status()).isEqualTo(OperatorAvailabilityStatus.PAUSED);
        assertThat(paused.get().activeCallId()).isEmpty();
        assertThat(presencePort.findAvailableBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .isEmpty();
        assertThat(presencePort.findBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .containsExactly(paused.get());

        Instant offlineAt = pausedAt.plusSeconds(3);
        presencePort.releaseOperator(operatorId, OperatorAvailabilityStatus.OFFLINE, offlineAt);
        Optional<OperatorPresence> offline = presencePort.findPresence(operatorId);
        assertThat(offline).isPresent();
        assertThat(offline.get().status()).isEqualTo(OperatorAvailabilityStatus.OFFLINE);
        assertThat(presencePort.tryClaimOperator(operatorId, CallId.generate(), offlineAt))
                .isFalse();
        assertThat(presencePort.findBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .containsExactly(offline.get());

        Instant reopenedAt = offlineAt.plusSeconds(3);
        presencePort.releaseOperator(operatorId, OperatorAvailabilityStatus.AVAILABLE, reopenedAt);
        assertThat(presencePort.findAvailableBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .extracting(OperatorPresence::operatorId)
                .containsExactly(operatorId);
    }

    @Test
    @DisplayName("Should extend live operator TTL on heartbeat and expire stale operators")
    void shouldExtendTtlOnHeartbeatAndExpireStaleOperators() {
        OperatorId renewedOp = OperatorId.fromString("op-renew-1");
        OperatorId staleOp = OperatorId.fromString("op-stale-1");
        Instant start = Instant.now();

        presencePort.savePresence(
                new OperatorPresence(
                        renewedOp,
                        "Active Operator",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.empty(),
                        start));
        presencePort.savePresence(
                new OperatorPresence(
                        staleOp,
                        "Stale Operator",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.empty(),
                        start));

        await().pollDelay(Duration.ofMillis(700))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(
                        () ->
                                assertThat(presencePort.refreshHeartbeat(renewedOp, Instant.now()))
                                        .isTrue());

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () -> {
                            assertThat(presencePort.findPresence(staleOp)).isEmpty();
                            assertThat(
                                            presencePort.findAvailableBySkillGroup(
                                                    SkillGroup.DISRUPTION_DESK_GOLD))
                                    .extracting(OperatorPresence::operatorId)
                                    .containsExactly(renewedOp);
                            assertThat(
                                            presencePort.findBySkillGroup(
                                                    SkillGroup.DISRUPTION_DESK_GOLD))
                                    .extracting(OperatorPresence::operatorId)
                                    .containsExactly(renewedOp);
                            assertThat(
                                            presencePort.tryClaimOperator(
                                                    staleOp, CallId.generate(), Instant.now()))
                                    .isFalse();
                            assertThat(presencePort.refreshHeartbeat(staleOp, Instant.now()))
                                    .isFalse();
                        });

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () -> {
                            assertThat(presencePort.findPresence(renewedOp)).isEmpty();
                            assertThat(
                                            presencePort.findAvailableBySkillGroup(
                                                    SkillGroup.DISRUPTION_DESK_GOLD))
                                    .isEmpty();
                            assertThat(
                                            presencePort.findBySkillGroup(
                                                    SkillGroup.DISRUPTION_DESK_GOLD))
                                    .isEmpty();
                        });
    }

    @Test
    @DisplayName("Should prevent double-assignment under concurrent atomic claim contention")
    void shouldPreventDoubleAssignmentUnderConcurrentClaimContention() throws Exception {
        OperatorId operatorId = OperatorId.fromString("op-concurrent-1");
        presencePort.savePresence(
                new OperatorPresence(
                        operatorId,
                        "Contended Operator",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY),
                        Optional.empty(),
                        BASE_TIME));

        int contenders = 20;
        CountDownLatch readyGate = new CountDownLatch(contenders);
        CountDownLatch startGate = new CountDownLatch(1);
        List<CallId> candidateCalls = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            candidateCalls.add(CallId.generate());
        }

        List<CallId> winningCalls = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Optional<CallId>>> tasks = new ArrayList<>();
            for (CallId candidate : candidateCalls) {
                tasks.add(
                        () -> {
                            readyGate.countDown();
                            startGate.await();
                            boolean claimed =
                                    presencePort.tryClaimOperator(
                                            operatorId, candidate, BASE_TIME.plusSeconds(1));
                            return claimed ? Optional.of(candidate) : Optional.empty();
                        });
            }

            List<Future<Optional<CallId>>> futures = new ArrayList<>();
            for (Callable<Optional<CallId>> task : tasks) {
                futures.add(executor.submit(task));
            }

            readyGate.await();
            startGate.countDown();

            for (Future<Optional<CallId>> future : futures) {
                future.get().ifPresent(winningCalls::add);
            }
        }

        assertThat(winningCalls).hasSize(1);
        Optional<OperatorPresence> claimedPresence = presencePort.findPresence(operatorId);
        assertThat(claimedPresence).isPresent();
        assertThat(claimedPresence.get().status()).isEqualTo(OperatorAvailabilityStatus.BUSY);
        assertThat(claimedPresence.get().activeCallId()).contains(winningCalls.getFirst());
    }

    @Test
    @DisplayName("Should preserve priority descending and FIFO arrival ordering per SkillGroup")
    void shouldPreservePriorityAndFifoOrderingPerSkillGroup() {
        Instant sameArrival = BASE_TIME.plusMillis(10);
        QueuedTicketItem lowPriority =
                sampleQueueItem(SkillGroup.DISRUPTION_DESK_GOLD, 50, BASE_TIME);
        QueuedTicketItem highFirst =
                sampleQueueItem(SkillGroup.DISRUPTION_DESK_GOLD, 100, sameArrival);
        QueuedTicketItem highSecondSameTimestamp =
                sampleQueueItem(SkillGroup.DISRUPTION_DESK_GOLD, 100, sameArrival);
        QueuedTicketItem mediumPriority =
                sampleQueueItem(SkillGroup.DISRUPTION_DESK_GOLD, 80, BASE_TIME);
        QueuedTicketItem accessibilityItem =
                sampleQueueItem(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY, 90, BASE_TIME);
        QueuedTicketItem standardItem =
                sampleQueueItem(SkillGroup.DISRUPTION_DESK_STANDARD, 50, BASE_TIME);

        assertThat(queuePort.enqueue(lowPriority)).isEqualTo(1);
        assertThat(queuePort.enqueue(highFirst)).isEqualTo(1);
        assertThat(queuePort.enqueue(highSecondSameTimestamp)).isEqualTo(2);
        assertThat(queuePort.enqueue(mediumPriority)).isEqualTo(3);
        assertThat(queuePort.enqueue(accessibilityItem)).isEqualTo(1);
        assertThat(queuePort.enqueue(standardItem)).isEqualTo(1);

        assertThat(queuePort.queueDepth(SkillGroup.DISRUPTION_DESK_GOLD)).isEqualTo(4L);
        assertThat(queuePort.queueDepth(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY)).isEqualTo(1L);
        assertThat(queuePort.queueDepth(SkillGroup.DISRUPTION_DESK_STANDARD)).isEqualTo(1L);

        assertThat(queuePort.listQueuedBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .containsExactly(highFirst, highSecondSameTimestamp, mediumPriority, lowPriority);

        assertThat(queuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD))
                .contains(highFirst);
        assertThat(queuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD))
                .contains(highSecondSameTimestamp);
        assertThat(queuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD))
                .contains(mediumPriority);
        assertThat(queuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD))
                .contains(lowPriority);
        assertThat(queuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD)).isEmpty();
        assertThat(queuePort.queueDepth(SkillGroup.DISRUPTION_DESK_GOLD)).isZero();

        assertThat(queuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY))
                .contains(accessibilityItem);
        assertThat(queuePort.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_STANDARD))
                .contains(standardItem);
    }

    @Test
    @DisplayName(
            "Should remove queued calls by CallId, support idempotent re-enqueue, and enforce"
                    + " capacity")
    void shouldRemoveByCallIdReEnqueueAndEnforceCapacity() {
        QueuedTicketItem first =
                sampleQueueItem(SkillGroup.DISRUPTION_DESK_PRIORITY, 80, BASE_TIME);
        QueuedTicketItem second =
                sampleQueueItem(SkillGroup.DISRUPTION_DESK_PRIORITY, 80, BASE_TIME.plusSeconds(1));
        QueuedTicketItem third =
                sampleQueueItem(SkillGroup.DISRUPTION_DESK_PRIORITY, 80, BASE_TIME.plusSeconds(2));

        queuePort.enqueue(first);
        queuePort.enqueue(second);
        queuePort.enqueue(third);

        assertThat(queuePort.removeByCallId(second.callId())).contains(second);
        assertThat(queuePort.removeByCallId(second.callId())).isEmpty();
        assertThat(queuePort.queueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY)).isEqualTo(2L);

        QueuedTicketItem reprioritizedThird =
                new QueuedTicketItem(
                        third.ticketEvent(),
                        SkillGroup.DISRUPTION_DESK_PRIORITY,
                        120,
                        third.enqueuedAt());
        assertThat(queuePort.enqueue(reprioritizedThird)).isEqualTo(1);
        assertThat(queuePort.queueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY)).isEqualTo(2L);

        queuePort.enqueue(sampleQueueItem(SkillGroup.DISRUPTION_DESK_PRIORITY, 60, BASE_TIME));
        queuePort.enqueue(sampleQueueItem(SkillGroup.DISRUPTION_DESK_PRIORITY, 60, BASE_TIME));
        queuePort.enqueue(sampleQueueItem(SkillGroup.DISRUPTION_DESK_PRIORITY, 60, BASE_TIME));

        assertThat(queuePort.queueDepth(SkillGroup.DISRUPTION_DESK_PRIORITY)).isEqualTo(5L);

        QueuedTicketItem overflow =
                sampleQueueItem(SkillGroup.DISRUPTION_DESK_PRIORITY, 95, BASE_TIME);
        assertThatThrownBy(() -> queuePort.enqueue(overflow))
                .isInstanceOf(RedisRoutingException.class)
                .hasMessageContaining("capacity exceeded");
    }

    @Test
    @DisplayName("Should execute reactive presence and queue pipelines via StepVerifier")
    void shouldExecuteReactivePipelinesWithStepVerifier() {
        OperatorId operatorId = OperatorId.fromString("op-reactive-1");
        OperatorPresence presence =
                new OperatorPresence(
                        operatorId,
                        "Reactive Operator",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_STANDARD),
                        Optional.empty(),
                        BASE_TIME);
        QueuedTicketItem item = sampleQueueItem(SkillGroup.DISRUPTION_DESK_STANDARD, 50, BASE_TIME);

        StepVerifier.create(presenceAdapter.savePresenceReactive(presence)).verifyComplete();
        StepVerifier.create(presenceAdapter.findPresenceReactive(operatorId))
                .expectNext(Optional.of(presence))
                .verifyComplete();

        StepVerifier.create(queueAdapter.enqueueReactive(item)).expectNext(1).verifyComplete();
        StepVerifier.create(
                        queueAdapter.dequeueHighestPriorityReactive(
                                SkillGroup.DISRUPTION_DESK_STANDARD))
                .expectNext(Optional.of(item))
                .verifyComplete();
    }
}
