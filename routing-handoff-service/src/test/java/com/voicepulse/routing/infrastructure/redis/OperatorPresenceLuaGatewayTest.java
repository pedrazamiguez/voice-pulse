package com.voicepulse.routing.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import com.voicepulse.routing.infrastructure.redis.config.RedisRoutingProperties;
import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceHash;
import com.voicepulse.shared.model.CallId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("OperatorPresenceLuaGateway Unit Tests")
class OperatorPresenceLuaGatewayTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:05:00Z");

    @Mock private ReactiveStringRedisTemplate stringRedisTemplate;

    @Captor private ArgumentCaptor<List<String>> keysCaptor;
    @Captor private ArgumentCaptor<List<String>> argsCaptor;

    private RedisKeySchema keySchema;
    private RedisLuaScripts luaScripts;
    private OperatorPresenceLuaGateway gateway;

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
        gateway =
                new OperatorPresenceLuaGateway(
                        stringRedisTemplate, keySchema, luaScripts, routingProps);
    }

    @Test
    @DisplayName("Should bind KEYS and ARGV cleanly when saving operator presence")
    void shouldBindSavePresenceScriptArguments() {
        OperatorId operatorId = OperatorId.fromString("op-save-1");
        OperatorPresenceHash hash =
                new OperatorPresenceHash(
                        "op-save-1",
                        "Sofia Garcia",
                        "AVAILABLE",
                        "DISRUPTION_DESK_GOLD,DISRUPTION_DESK_PRIORITY",
                        "",
                        NOW.toString());

        when(stringRedisTemplate.execute(
                        eq(luaScripts.saveOperatorPresenceScript()), anyList(), anyList()))
                .thenReturn(Flux.just(1L));

        StepVerifier.create(
                        gateway.savePresence(
                                operatorId,
                                hash,
                                Set.of(
                                        SkillGroup.DISRUPTION_DESK_GOLD,
                                        SkillGroup.DISRUPTION_DESK_PRIORITY),
                                true))
                .verifyComplete();

        verify(stringRedisTemplate)
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
                        "op-save-1",
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
    @DisplayName("Should bind KEYS and ARGV for claim, release, and heartbeat refresh scripts")
    void shouldBindClaimReleaseAndHeartbeatScripts() {
        OperatorId operatorId = OperatorId.fromString("op-tx-1");
        CallId callId = CallId.generate();

        when(stringRedisTemplate.execute(
                        eq(luaScripts.claimOperatorScript()), anyList(), anyList()))
                .thenReturn(Flux.just(1L));
        when(stringRedisTemplate.execute(
                        eq(luaScripts.releaseOperatorScript()), anyList(), anyList()))
                .thenReturn(Flux.just(1L));
        when(stringRedisTemplate.execute(
                        eq(luaScripts.refreshHeartbeatScript()), anyList(), anyList()))
                .thenReturn(Flux.just(0L));

        StepVerifier.create(gateway.tryClaimOperator(operatorId, callId, NOW))
                .expectNext(true)
                .verifyComplete();

        StepVerifier.create(
                        gateway.releaseOperator(
                                operatorId, OperatorAvailabilityStatus.AVAILABLE, NOW))
                .verifyComplete();

        StepVerifier.create(gateway.refreshHeartbeat(operatorId, NOW))
                .expectNext(false)
                .verifyComplete();

        verify(stringRedisTemplate)
                .execute(
                        eq(luaScripts.claimOperatorScript()),
                        keysCaptor.capture(),
                        argsCaptor.capture());
        assertThat(argsCaptor.getValue())
                .containsExactly("op-tx-1", callId.toString(), NOW.toString(), "30000");

        verify(stringRedisTemplate)
                .execute(
                        eq(luaScripts.releaseOperatorScript()),
                        keysCaptor.capture(),
                        argsCaptor.capture());
        assertThat(argsCaptor.getValue())
                .startsWith("op-tx-1", "AVAILABLE", NOW.toString(), "30000");
    }

    @Test
    @DisplayName("Should short-circuit empty eviction batch and bind all skill keys atomically")
    void shouldHandleEmptyAndNonEmptyAtomicEviction() {
        StepVerifier.create(gateway.evictExpiredOperators(List.of()))
                .expectNext(0L)
                .verifyComplete();
        verifyNoInteractions(stringRedisTemplate);

        OperatorId first = OperatorId.fromString("op-exp-1");
        OperatorId second = OperatorId.fromString("op-exp-2");
        when(stringRedisTemplate.execute(
                        eq(luaScripts.evictExpiredOperatorsScript()), anyList(), anyList()))
                .thenReturn(Flux.just(6L));

        StepVerifier.create(gateway.evictExpiredOperators(List.of(first, second, first)))
                .expectNext(6L)
                .verifyComplete();

        verify(stringRedisTemplate)
                .execute(
                        eq(luaScripts.evictExpiredOperatorsScript()),
                        keysCaptor.capture(),
                        argsCaptor.capture());

        assertThat(keysCaptor.getValue().subList(0, 2))
                .containsExactly(
                        keySchema.operatorPresenceKey(first),
                        keySchema.operatorPresenceKey(second));
        assertThat(keysCaptor.getValue())
                .containsAll(keySchema.allSkillAvailableKeys())
                .containsAll(keySchema.allSkillOperatorsKeys());
        assertThat(argsCaptor.getValue()).containsExactly("2", "op-exp-1", "op-exp-2");
    }
}
