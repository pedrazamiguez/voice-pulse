package com.voicepulse.routing.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.redis.config.RedisRoutingProperties;
import com.voicepulse.routing.infrastructure.redis.mapper.OperatorPresenceHashMapper;
import com.voicepulse.routing.infrastructure.redis.mapper.RedisRoutingMapper;
import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceHash;
import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceReadSnapshot;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisOperatorPresenceAdapter Unit Tests")
class RedisOperatorPresenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:45:00Z");

    @Mock private ReactiveStringRedisTemplate redisTemplate;
    @Mock private ReactiveSetOperations<String, String> setOperations;
    @Mock private OperatorPresenceBatchReader batchReader;
    @Mock private OperatorPresenceLuaGateway luaGateway;

    private RedisKeySchema keySchema;
    private OperatorPresenceHashMapper hashMapper;
    private RedisRoutingMapper mapper;
    private RedisOperatorPresenceAdapter adapter;

    @BeforeEach
    void setUp() {
        RedisRoutingProperties redisProps =
                new RedisRoutingProperties(true, "voicepulse:routing", Duration.ofSeconds(5));
        keySchema = new RedisKeySchema(redisProps);
        hashMapper = new OperatorPresenceHashMapper();
        mapper = Mappers.getMapper(RedisRoutingMapper.class);
        RedisReactiveExecutor executor = new RedisReactiveExecutor(redisProps);

        adapter =
                new RedisOperatorPresenceAdapter(
                        redisTemplate,
                        keySchema,
                        batchReader,
                        luaGateway,
                        hashMapper,
                        mapper,
                        executor);

        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    @DisplayName("Should delegate savePresence to OperatorPresenceLuaGateway")
    void shouldSavePresenceViaGateway() {
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
        OperatorPresenceHash expectedHash = mapper.toPresenceHash(presence);

        when(luaGateway.savePresence(operatorId, expectedHash, presence.skills(), true))
                .thenReturn(Mono.empty());

        adapter.savePresence(presence);

        verify(luaGateway).savePresence(operatorId, expectedHash, presence.skills(), true);
    }

    @Test
    @DisplayName("Should return mapped presence on active single lookup")
    void shouldFindPresenceWhenHeartbeatActive() {
        OperatorId operatorId = OperatorId.fromString("op-active-1");
        OperatorPresence expected =
                new OperatorPresence(
                        operatorId,
                        "Maria Lopez",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.empty(),
                        NOW);
        Map<String, String> entries = hashMapper.toHash(mapper.toPresenceHash(expected));

        when(batchReader.readOperatorPresences(List.of(operatorId), true))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new OperatorPresenceReadSnapshot(
                                                operatorId, true, entries))));

        assertThat(adapter.findPresence(operatorId)).contains(expected);
        verifyNoInteractions(luaGateway);
    }

    @Test
    @DisplayName("Should return empty and evict atomically when expired presence hash remains")
    void shouldReturnEmptyAndEvictWhenHeartbeatExpired() {
        OperatorId operatorId = OperatorId.fromString("op-stale-1");

        when(batchReader.readOperatorPresences(List.of(operatorId), true))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new OperatorPresenceReadSnapshot(
                                                operatorId,
                                                false,
                                                Map.of(
                                                        "operatorId", "op-stale-1",
                                                        "status", "AVAILABLE")))));
        when(luaGateway.evictExpiredOperators(List.of(operatorId))).thenReturn(Mono.just(3L));

        Optional<OperatorPresence> result = adapter.findPresence(operatorId);

        assertThat(result).isEmpty();
        verify(luaGateway).evictExpiredOperators(List.of(operatorId));
    }

    @Test
    @DisplayName("Should avoid eviction writes when querying unknown operator without hash entries")
    void shouldAvoidCleanupWritesForUnknownOperator() {
        OperatorId operatorId = OperatorId.fromString("op-unknown-1");
        when(batchReader.readOperatorPresences(List.of(operatorId), true))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new OperatorPresenceReadSnapshot(
                                                operatorId, false, Map.of()))));

        assertThat(adapter.findPresence(operatorId)).isEmpty();
        verify(luaGateway, never()).evictExpiredOperators(anyList());
    }

    @Test
    @DisplayName(
            "Should batch-read skill members, evict expired batch, and prune active stale"
                    + " members")
    void shouldBatchEvictExpiredAndPruneNonAvailableMembers() {
        OperatorId availableOp = OperatorId.fromString("op-avail-1");
        OperatorId pausedOp = OperatorId.fromString("op-paused-1");
        OperatorId expiredOp1 = OperatorId.fromString("op-exp-1");
        OperatorId expiredOp2 = OperatorId.fromString("op-exp-2");
        String availableSetKey = keySchema.skillAvailableKey(SkillGroup.DISRUPTION_DESK_GOLD);

        when(setOperations.members(availableSetKey))
                .thenReturn(Flux.just("op-avail-1", "op-paused-1", "op-exp-1", "op-exp-2"));

        OperatorPresence availablePresence =
                new OperatorPresence(
                        availableOp,
                        "Maria Lopez",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.empty(),
                        NOW);
        OperatorPresence pausedPresence =
                new OperatorPresence(
                        pausedOp,
                        "Pedro Ruiz",
                        OperatorAvailabilityStatus.PAUSED,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.empty(),
                        NOW);

        when(batchReader.readOperatorPresences(
                        List.of(availableOp, pausedOp, expiredOp1, expiredOp2), false))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new OperatorPresenceReadSnapshot(
                                                availableOp,
                                                true,
                                                hashMapper.toHash(
                                                        mapper.toPresenceHash(availablePresence))),
                                        new OperatorPresenceReadSnapshot(
                                                pausedOp,
                                                true,
                                                hashMapper.toHash(
                                                        mapper.toPresenceHash(pausedPresence))),
                                        new OperatorPresenceReadSnapshot(
                                                expiredOp1, false, Map.of()),
                                        new OperatorPresenceReadSnapshot(
                                                expiredOp2, false, Map.of()))));

        when(luaGateway.evictExpiredOperators(List.of(expiredOp1, expiredOp2)))
                .thenReturn(Mono.just(6L));
        when(setOperations.remove(availableSetKey, "op-paused-1")).thenReturn(Mono.just(1L));

        StepVerifier.create(
                        adapter.findAvailableBySkillGroupReactive(SkillGroup.DISRUPTION_DESK_GOLD))
                .assertNext(list -> assertThat(list).containsExactly(availablePresence))
                .verifyComplete();

        verify(luaGateway).evictExpiredOperators(List.of(expiredOp1, expiredOp2));
        verify(setOperations).remove(availableSetKey, "op-paused-1");
    }

    @Test
    @DisplayName(
            "Should include BUSY operators and prune skill-mismatched members in findBySkillGroup")
    void shouldIncludeBusyAndPruneWrongSkillMembersInFindBySkillGroup() {
        OperatorId busyOp = OperatorId.fromString("op-busy-1");
        OperatorId mismatchOp = OperatorId.fromString("op-other-skill");
        String operatorsSetKey = keySchema.skillOperatorsKey(SkillGroup.DISRUPTION_DESK_GOLD);
        CallId activeCallId = CallId.generate();

        when(setOperations.members(operatorsSetKey))
                .thenReturn(Flux.just("op-busy-1", "op-other-skill"));

        OperatorPresence busyPresence =
                new OperatorPresence(
                        busyOp,
                        "Carlos Diaz",
                        OperatorAvailabilityStatus.BUSY,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.of(activeCallId),
                        NOW);
        OperatorPresence otherSkillPresence =
                new OperatorPresence(
                        mismatchOp,
                        "Lucia Fernandez",
                        OperatorAvailabilityStatus.AVAILABLE,
                        Set.of(SkillGroup.DISRUPTION_DESK_STANDARD),
                        Optional.empty(),
                        NOW);

        when(batchReader.readOperatorPresences(List.of(busyOp, mismatchOp), false))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new OperatorPresenceReadSnapshot(
                                                busyOp,
                                                true,
                                                hashMapper.toHash(
                                                        mapper.toPresenceHash(busyPresence))),
                                        new OperatorPresenceReadSnapshot(
                                                mismatchOp,
                                                true,
                                                hashMapper.toHash(
                                                        mapper.toPresenceHash(
                                                                otherSkillPresence))))));
        when(luaGateway.evictExpiredOperators(List.of())).thenReturn(Mono.just(0L));
        when(setOperations.remove(operatorsSetKey, "op-other-skill")).thenReturn(Mono.just(1L));

        assertThat(adapter.findBySkillGroup(SkillGroup.DISRUPTION_DESK_GOLD))
                .containsExactly(busyPresence);
        verify(setOperations).remove(operatorsSetKey, "op-other-skill");
    }

    @Test
    @DisplayName("Should delegate claim, release, and heartbeat refresh to Lua gateway")
    void shouldDelegateClaimReleaseAndHeartbeatRefresh() {
        OperatorId operatorId = OperatorId.fromString("op-claim-1");
        CallId callId = CallId.generate();

        when(luaGateway.tryClaimOperator(operatorId, callId, NOW)).thenReturn(Mono.just(true));
        when(luaGateway.releaseOperator(operatorId, OperatorAvailabilityStatus.AVAILABLE, NOW))
                .thenReturn(Mono.empty());
        when(luaGateway.refreshHeartbeat(operatorId, NOW)).thenReturn(Mono.just(false));

        assertThat(adapter.tryClaimOperator(operatorId, callId, NOW)).isTrue();
        adapter.releaseOperator(operatorId, OperatorAvailabilityStatus.AVAILABLE, NOW);
        assertThat(adapter.refreshHeartbeat(operatorId, NOW)).isFalse();
    }

    @Test
    @DisplayName("Should wrap gateway execution errors in RedisRoutingException")
    void shouldWrapRedisErrorsInRedisRoutingException() {
        OperatorId operatorId = OperatorId.fromString("op-err-1");
        when(luaGateway.refreshHeartbeat(operatorId, NOW))
                .thenReturn(Mono.error(new IllegalStateException("Redis connection lost")));

        assertThatThrownBy(() -> adapter.refreshHeartbeat(operatorId, NOW))
                .isInstanceOf(RedisRoutingException.class)
                .hasMessageContaining("refreshHeartbeat");
    }
}
