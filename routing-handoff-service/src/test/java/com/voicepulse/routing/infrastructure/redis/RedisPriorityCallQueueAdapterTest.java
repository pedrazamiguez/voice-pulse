package com.voicepulse.routing.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import com.voicepulse.routing.infrastructure.redis.config.RedisRoutingProperties;
import com.voicepulse.routing.infrastructure.redis.mapper.RedisRoutingMapper;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisPriorityCallQueueAdapter Unit Tests")
class RedisPriorityCallQueueAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:50:00Z");

    @Mock private ReactiveStringRedisTemplate redisTemplate;
    @Mock private ReactiveZSetOperations<String, String> zSetOperations;
    @Mock private ReactiveHashOperations<String, String, String> hashOperations;

    @Captor private ArgumentCaptor<List<String>> argsCaptor;

    private RedisKeySchema keySchema;
    private RedisLuaScripts luaScripts;
    private RedisRoutingMapper mapper;
    private ObjectMapper objectMapper;
    private RedisPriorityCallQueueAdapter adapter;

    @BeforeEach
    void setUp() {
        RedisRoutingProperties redisProps =
                new RedisRoutingProperties(true, "voicepulse:routing", Duration.ofSeconds(5));
        RoutingProperties routingProps =
                new RoutingProperties(
                        "telephony.tickets.intent",
                        "telephony.calls.control",
                        "routing-handoff-workers",
                        Duration.ofSeconds(30),
                        "/ws/operators",
                        100);
        keySchema = new RedisKeySchema(redisProps);
        luaScripts = new RedisLuaScripts();
        mapper = Mappers.getMapper(RedisRoutingMapper.class);
        objectMapper = KafkaJsonCodecProvider.objectMapper();
        RedisReactiveExecutor executor = new RedisReactiveExecutor(redisProps);

        adapter =
                new RedisPriorityCallQueueAdapter(
                        redisTemplate,
                        keySchema,
                        luaScripts,
                        mapper,
                        objectMapper,
                        routingProps,
                        executor);

        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        lenient().when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
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
    @DisplayName("Should enqueue item with negative priority score and return 1-based position")
    void shouldEnqueueAndReturnOneBasedPosition() {
        QueuedTicketItem item = sampleQueuedItem(SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW);
        when(redisTemplate.execute(
                        eq(luaScripts.enqueuePriorityCallScript()), anyList(), anyList()))
                .thenReturn(Flux.just(2L));

        int position = adapter.enqueue(item);

        assertThat(position).isEqualTo(2);
        verify(redisTemplate)
                .execute(
                        eq(luaScripts.enqueuePriorityCallScript()),
                        anyList(),
                        argsCaptor.capture());
        assertThat(argsCaptor.getValue().get(0)).isEqualTo(item.callId().toString());
        assertThat(argsCaptor.getValue().get(1)).isEqualTo("DISRUPTION_DESK_GOLD");
        assertThat(argsCaptor.getValue().get(2)).isEqualTo("-100");
        assertThat(argsCaptor.getValue().get(5)).isEqualTo("100");
    }

    @Test
    @DisplayName("Should throw RedisRoutingException when queue capacity is exceeded")
    void shouldThrowWhenQueueCapacityExceeded() {
        QueuedTicketItem item = sampleQueuedItem(SkillGroup.DISRUPTION_DESK_STANDARD, 50, NOW);
        when(redisTemplate.execute(
                        eq(luaScripts.enqueuePriorityCallScript()), anyList(), anyList()))
                .thenReturn(Flux.just(-1L));

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
    @DisplayName("Should deserialize dequeued and removed items cleanly")
    void shouldDeserializeDequeuedAndRemovedItems() throws Exception {
        QueuedTicketItem item = sampleQueuedItem(SkillGroup.DISRUPTION_DESK_PRIORITY, 80, NOW);
        String json = objectMapper.writeValueAsString(mapper.toRedisPayload(item));

        when(redisTemplate.execute(
                        eq(luaScripts.dequeuePriorityCallScript()), anyList(), anyList()))
                .thenReturn(Flux.just(json));
        when(redisTemplate.execute(eq(luaScripts.removePriorityCallScript()), anyList(), anyList()))
                .thenReturn(Flux.empty());

        Optional<QueuedTicketItem> dequeued =
                adapter.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_PRIORITY);
        Optional<QueuedTicketItem> removed = adapter.removeByCallId(CallId.generate());

        assertThat(dequeued).contains(item);
        assertThat(removed).isEmpty();
    }

    @Test
    @DisplayName("Should list queued items in ZSET priority-FIFO order and report queue depth")
    void shouldListQueuedItemsAndDepth() throws Exception {
        QueuedTicketItem first = sampleQueuedItem(SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW);
        QueuedTicketItem second =
                sampleQueuedItem(SkillGroup.DISRUPTION_DESK_GOLD, 90, NOW.plusSeconds(1));
        String firstJson = objectMapper.writeValueAsString(mapper.toRedisPayload(first));
        String secondJson = objectMapper.writeValueAsString(mapper.toRedisPayload(second));

        String firstMember = "800000006af81b7c:000000000:000000000000001:" + first.callId();
        String secondMember = "800000006af81b7d:000000000:000000000000002:" + second.callId();

        when(zSetOperations.range(
                        eq(keySchema.queueKey(SkillGroup.DISRUPTION_DESK_GOLD)), any(Range.class)))
                .thenReturn(Flux.just(firstMember, secondMember));
        when(hashOperations.multiGet(
                        keySchema.queueItemsKey(),
                        List.of(first.callId().toString(), second.callId().toString())))
                .thenReturn(Mono.just(List.of(firstJson, secondJson)));
        when(zSetOperations.size(keySchema.queueKey(SkillGroup.DISRUPTION_DESK_GOLD)))
                .thenReturn(Mono.just(2L));

        StepVerifier.create(adapter.listQueuedBySkillGroupReactive(SkillGroup.DISRUPTION_DESK_GOLD))
                .expectNext(List.of(first, second))
                .verifyComplete();

        assertThat(adapter.queueDepth(SkillGroup.DISRUPTION_DESK_GOLD)).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should reject malformed ZSET members or corrupt JSON payloads")
    void shouldFailOnMalformedMemberOrCorruptJson() {
        assertThatThrownBy(() -> RedisPriorityCallQueueAdapter.extractCallIdFromMember("invalid"))
                .isInstanceOf(RedisRoutingException.class);

        when(redisTemplate.execute(
                        eq(luaScripts.dequeuePriorityCallScript()), anyList(), anyList()))
                .thenReturn(Flux.just("{not-valid-json"));

        assertThatThrownBy(() -> adapter.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD))
                .isInstanceOf(RedisRoutingException.class);
    }
}
