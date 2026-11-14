package com.voicepulse.routing.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import com.voicepulse.routing.infrastructure.redis.config.RedisRoutingProperties;
import com.voicepulse.routing.infrastructure.redis.mapper.RedisRoutingMapper;
import com.voicepulse.shared.model.CallId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisOperatorPresenceAdapter Unit Tests")
class RedisOperatorPresenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:45:00Z");

    @Mock private ReactiveStringRedisTemplate redisTemplate;
    @Mock private ReactiveHashOperations<String, String, String> hashOperations;
    @Mock private ReactiveSetOperations<String, String> setOperations;

    @Captor private ArgumentCaptor<List<String>> keysCaptor;
    @Captor private ArgumentCaptor<List<String>> argsCaptor;

    private RedisKeySchema keySchema;
    private RedisLuaScripts luaScripts;
    private RedisOperatorPresenceAdapter adapter;

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
                        1000);
        keySchema = new RedisKeySchema(redisProps);
        luaScripts = new RedisLuaScripts();
        RedisRoutingMapper mapper = Mappers.getMapper(RedisRoutingMapper.class);
        RedisReactiveExecutor executor = new RedisReactiveExecutor(redisProps);

        adapter =
                new RedisOperatorPresenceAdapter(
                        redisTemplate, keySchema, luaScripts, mapper, routingProps, executor);

        lenient().when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    @DisplayName("Should execute atomic Lua script when saving operator presence")
    void shouldSavePresenceViaLuaScript() {
        OperatorId operatorId = OperatorId.fromString("op-unit-1");
        OperatorPresence presence =
                new OperatorPresence(
                        operatorId,
                        "Sofia Garcia",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(
                                SkillGroup.DISRUPTION_DESK_GOLD,
                                SkillGroup.DISRUPTION_DESK_PRIORITY),
                        Optional.empty(),
                        NOW);

        when(redisTemplate.execute(
                        eq(luaScripts.saveOperatorPresenceScript()), anyList(), anyList()))
                .thenReturn(Flux.just(1L));

        adapter.savePresence(presence);

        verify(redisTemplate)
                .execute(
                        eq(luaScripts.saveOperatorPresenceScript()),
                        keysCaptor.capture(),
                        argsCaptor.capture());

        assertThat(keysCaptor.getValue())
                .startsWith(
                        keySchema.operatorPresenceKey(operatorId),
                        keySchema.operatorHeartbeatKey(operatorId));
        assertThat(argsCaptor.getValue())
                .containsExactly(
                        "op-unit-1",
                        "Sofia Garcia",
                        "AVAILABLE",
                        "DISRUPTION_DESK_GOLD,DISRUPTION_DESK_PRIORITY",
                        "",
                        NOW.toString(),
                        "30000",
                        "1",
                        "4",
                        "1",
                        "0",
                        "1",
                        "0");
    }

    @Test
    @DisplayName("Should return empty and evict stale presence when heartbeat key expired")
    void shouldReturnEmptyAndEvictWhenHeartbeatExpired() {
        OperatorId operatorId = OperatorId.fromString("op-stale-1");
        String presenceKey = keySchema.operatorPresenceKey(operatorId);
        String heartbeatKey = keySchema.operatorHeartbeatKey(operatorId);

        when(redisTemplate.hasKey(heartbeatKey)).thenReturn(Mono.just(false));
        when(hashOperations.entries(presenceKey))
                .thenReturn(
                        Flux.fromIterable(
                                Map.of("operatorId", "op-stale-1", "status", "AVAILABLE")
                                        .entrySet()));
        when(redisTemplate.delete(presenceKey)).thenReturn(Mono.just(1L));
        when(setOperations.remove(any(String.class), eq("op-stale-1"))).thenReturn(Mono.just(1L));

        Optional<OperatorPresence> result = adapter.findPresence(operatorId);

        assertThat(result).isEmpty();
        verify(redisTemplate).delete(presenceKey);
    }

    @Test
    @DisplayName("Should avoid Redis deletions when querying unknown operator without hash entries")
    void shouldAvoidCleanupWritesForUnknownOperator() {
        OperatorId operatorId = OperatorId.fromString("op-unknown-1");
        when(redisTemplate.hasKey(keySchema.operatorHeartbeatKey(operatorId)))
                .thenReturn(Mono.just(false));
        when(hashOperations.entries(keySchema.operatorPresenceKey(operatorId)))
                .thenReturn(Flux.empty());

        assertThat(adapter.findPresence(operatorId)).isEmpty();
        verify(redisTemplate, never()).delete(any(String.class));
    }

    @Test
    @DisplayName("Should prune non-available or expired members when listing available operators")
    void shouldPruneNonAvailableMembersDuringFindAvailableBySkillGroup() {
        OperatorId availableOp = OperatorId.fromString("op-avail-1");
        OperatorId pausedOp = OperatorId.fromString("op-paused-1");

        when(setOperations.members(keySchema.skillAvailableKey(SkillGroup.DISRUPTION_DESK_GOLD)))
                .thenReturn(Flux.just("op-avail-1", "op-paused-1"));

        when(redisTemplate.hasKey(keySchema.operatorHeartbeatKey(availableOp)))
                .thenReturn(Mono.just(true));
        when(hashOperations.entries(keySchema.operatorPresenceKey(availableOp)))
                .thenReturn(
                        Flux.fromIterable(
                                Map.of(
                                                "operatorId", "op-avail-1",
                                                "displayName", "Maria Lopez",
                                                "status", "AVAILABLE",
                                                "skills", "DISRUPTION_DESK_GOLD",
                                                "activeCallId", "",
                                                "lastHeartbeatAt", NOW.toString())
                                        .entrySet()));

        when(redisTemplate.hasKey(keySchema.operatorHeartbeatKey(pausedOp)))
                .thenReturn(Mono.just(true));
        when(hashOperations.entries(keySchema.operatorPresenceKey(pausedOp)))
                .thenReturn(
                        Flux.fromIterable(
                                Map.of(
                                                "operatorId", "op-paused-1",
                                                "displayName", "Pedro Ruiz",
                                                "status", "PAUSED",
                                                "skills", "DISRUPTION_DESK_GOLD",
                                                "activeCallId", "",
                                                "lastHeartbeatAt", NOW.toString())
                                        .entrySet()));
        when(setOperations.remove(
                        keySchema.skillAvailableKey(SkillGroup.DISRUPTION_DESK_GOLD),
                        "op-paused-1"))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(
                        adapter.findAvailableBySkillGroupReactive(SkillGroup.DISRUPTION_DESK_GOLD))
                .assertNext(
                        list -> {
                            assertThat(list).hasSize(1);
                            assertThat(list.getFirst().operatorId()).isEqualTo(availableOp);
                        })
                .verifyComplete();

        verify(setOperations)
                .remove(
                        keySchema.skillAvailableKey(SkillGroup.DISRUPTION_DESK_GOLD),
                        "op-paused-1");
    }

    @Test
    @DisplayName("Should return boolean claim and heartbeat refresh results via Lua scripts")
    void shouldHandleAtomicClaimAndHeartbeatRefresh() {
        OperatorId operatorId = OperatorId.fromString("op-claim-1");
        CallId callId = CallId.generate();

        when(redisTemplate.execute(eq(luaScripts.claimOperatorScript()), anyList(), anyList()))
                .thenReturn(Flux.just(1L));
        when(redisTemplate.execute(eq(luaScripts.refreshHeartbeatScript()), anyList(), anyList()))
                .thenReturn(Flux.just(0L));

        assertThat(adapter.tryClaimOperator(operatorId, callId, NOW)).isTrue();
        assertThat(adapter.refreshHeartbeat(operatorId, NOW)).isFalse();
    }

    @Test
    @DisplayName("Should wrap Redis execution errors in RedisRoutingException")
    @SuppressWarnings("unchecked")
    void shouldWrapRedisErrorsInRedisRoutingException() {
        OperatorId operatorId = OperatorId.fromString("op-err-1");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("Redis connection lost")));

        assertThatThrownBy(() -> adapter.refreshHeartbeat(operatorId, NOW))
                .isInstanceOf(RedisRoutingException.class)
                .hasMessageContaining("refreshHeartbeat");
    }
}
