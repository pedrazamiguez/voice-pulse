package com.voicepulse.routing.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.redis.config.RedisRoutingProperties;
import com.voicepulse.routing.infrastructure.redis.mapper.RedisRoutingMapper;
import com.voicepulse.routing.infrastructure.redis.model.QueuedTicketRedisPayload;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisPriorityCallQueueAdapter Unit Tests")
class RedisPriorityCallQueueAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:50:00Z");

    @Mock private ReactiveStringRedisTemplate stringRedisTemplate;

    @Mock private ReactiveRedisTemplate<String, QueuedTicketRedisPayload> queuedTicketRedisTemplate;

    @Mock private ReactiveZSetOperations<String, String> zSetOperations;

    @Mock
    private ReactiveHashOperations<String, String, QueuedTicketRedisPayload> typedHashOperations;

    @Mock private PriorityCallQueueLuaGateway luaGateway;

    private RedisKeySchema keySchema;
    private RedisRoutingMapper mapper;
    private RedisPriorityCallQueueAdapter adapter;

    @BeforeEach
    void setUp() {
        RedisRoutingProperties redisProps =
                new RedisRoutingProperties(true, "voicepulse:routing", Duration.ofSeconds(5));
        keySchema = new RedisKeySchema(redisProps);
        mapper = Mappers.getMapper(RedisRoutingMapper.class);
        RedisReactiveExecutor executor = new RedisReactiveExecutor(redisProps);

        adapter =
                new RedisPriorityCallQueueAdapter(
                        stringRedisTemplate,
                        queuedTicketRedisTemplate,
                        keySchema,
                        luaGateway,
                        mapper,
                        executor);

        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        lenient()
                .when(queuedTicketRedisTemplate.<String, QueuedTicketRedisPayload>opsForHash())
                .thenReturn(typedHashOperations);
    }

    private static QueuedTicketItem sampleQueuedItem(
            SkillGroup skillGroup, int priorityScore, Instant enqueuedAt) {
        TicketFormFilledEvent event =
                Instancio.of(TicketFormFilledEvent.class)
                        .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                        .set(
                                field(TicketFormFilledEvent::originalFlightNumber),
                                new FlightNumber("SW-104"))
                        .set(field(TicketFormFilledEvent::targetSkillGroup), skillGroup.name())
                        .set(field(TicketFormFilledEvent::payload), Map.of("hotel_rooms", 1))
                        .set(field(TicketFormFilledEvent::timestamp), NOW)
                        .create();
        return new QueuedTicketItem(event, skillGroup, priorityScore, enqueuedAt);
    }

    @Test
    @DisplayName("Should delegate enqueue to PriorityCallQueueLuaGateway and return 1-based rank")
    void shouldEnqueueViaGateway() {
        QueuedTicketItem item = sampleQueuedItem(SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW);
        QueuedTicketRedisPayload payload = mapper.toRedisPayload(item);

        when(luaGateway.enqueue(payload, item.callId(), SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW))
                .thenReturn(Mono.just(2));

        int position = adapter.enqueue(item);

        assertThat(position).isEqualTo(2);
        verify(luaGateway)
                .enqueue(payload, item.callId(), SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW);
    }

    @Test
    @DisplayName("Should propagate RedisRoutingException from gateway when capacity is exceeded")
    void shouldPropagateQueueCapacityExceededError() {
        QueuedTicketItem item = sampleQueuedItem(SkillGroup.DISRUPTION_DESK_STANDARD, 50, NOW);
        QueuedTicketRedisPayload payload = mapper.toRedisPayload(item);

        when(luaGateway.enqueue(
                        payload, item.callId(), SkillGroup.DISRUPTION_DESK_STANDARD, 50, NOW))
                .thenReturn(
                        Mono.error(new RedisRoutingException("Priority queue capacity exceeded")));

        assertThatThrownBy(() -> adapter.enqueue(item))
                .isInstanceOf(RedisRoutingException.class)
                .hasMessageContaining("capacity exceeded");
    }

    @Test
    @DisplayName("Should format timestamp prefix strictly ascending across chronological instants")
    void shouldFormatChronologicalTimestampPrefixes() {
        String earlier =
                RedisPriorityCallQueueAdapter.formatTimestampPrefix(
                        Instant.parse("2026-11-14T12:00:00.000000001Z"));
        String laterSameSecond =
                RedisPriorityCallQueueAdapter.formatTimestampPrefix(
                        Instant.parse("2026-11-14T12:00:00.000000002Z"));
        String laterSecond =
                RedisPriorityCallQueueAdapter.formatTimestampPrefix(
                        Instant.parse("2026-11-14T12:00:01Z"));

        assertThat(earlier).isLessThan(laterSameSecond);
        assertThat(laterSameSecond).isLessThan(laterSecond);
    }

    @Test
    @DisplayName("Should map typed payloads returned by gateway on dequeue and removeByCallId")
    void shouldMapGatewayPayloadsOnDequeueAndRemove() {
        QueuedTicketItem item = sampleQueuedItem(SkillGroup.DISRUPTION_DESK_PRIORITY, 80, NOW);
        QueuedTicketRedisPayload payload = mapper.toRedisPayload(item);
        CallId missingCallId = CallId.generate();

        when(luaGateway.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_PRIORITY))
                .thenReturn(Mono.just(Optional.of(payload)));
        when(luaGateway.removeByCallId(missingCallId)).thenReturn(Mono.just(Optional.empty()));

        Optional<QueuedTicketItem> dequeued =
                adapter.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_PRIORITY);
        Optional<QueuedTicketItem> removed = adapter.removeByCallId(missingCallId);

        assertThat(dequeued).contains(item);
        assertThat(removed).isEmpty();
    }

    @Test
    @DisplayName(
            "Should list typed queued items via ReactiveRedisTemplate multiGet and report queue"
                    + " depth")
    void shouldListQueuedItemsViaTypedMultiGetAndReportDepth() {
        QueuedTicketItem first = sampleQueuedItem(SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW);
        QueuedTicketItem second =
                sampleQueuedItem(SkillGroup.DISRUPTION_DESK_GOLD, 90, NOW.plusSeconds(1));
        QueuedTicketRedisPayload firstPayload = mapper.toRedisPayload(first);
        QueuedTicketRedisPayload secondPayload = mapper.toRedisPayload(second);

        String firstMember = "800000006af81b7c:000000000:000000000000001:" + first.callId();
        String missingMember = "800000006af81b7c:000000000:000000000000002:" + CallId.generate();
        String secondMember = "800000006af81b7d:000000000:000000000000003:" + second.callId();

        when(zSetOperations.range(
                        eq(keySchema.queueKey(SkillGroup.DISRUPTION_DESK_GOLD)), any(Range.class)))
                .thenReturn(Flux.just(firstMember, missingMember, secondMember));
        when(typedHashOperations.multiGet(eq(keySchema.queueItemsKey()), any()))
                .thenReturn(Mono.just(Arrays.asList(firstPayload, null, secondPayload)));
        when(zSetOperations.size(keySchema.queueKey(SkillGroup.DISRUPTION_DESK_GOLD)))
                .thenReturn(Mono.just(2L));

        StepVerifier.create(adapter.listQueuedBySkillGroupReactive(SkillGroup.DISRUPTION_DESK_GOLD))
                .expectNext(List.of(first, second))
                .verifyComplete();

        assertThat(adapter.queueDepth(SkillGroup.DISRUPTION_DESK_GOLD)).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should reject malformed ZSET members")
    void shouldFailOnMalformedMember() {
        assertThatThrownBy(() -> RedisPriorityCallQueueAdapter.extractCallIdFromMember("invalid"))
                .isInstanceOf(RedisRoutingException.class);
    }
}
