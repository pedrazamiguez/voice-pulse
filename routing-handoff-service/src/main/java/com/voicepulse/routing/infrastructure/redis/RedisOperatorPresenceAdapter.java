package com.voicepulse.routing.infrastructure.redis;

import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import com.voicepulse.routing.infrastructure.redis.mapper.RedisRoutingMapper;
import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceHash;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reactive Redis 7 outbound adapter implementing {@link OperatorPresencePort}.
 *
 * <p>Uses {@link ReactiveStringRedisTemplate} and atomic Lua scripts for TTL-enforced operator
 * presence snapshots, heartbeat renewal, and mutually exclusive {@code AVAILABLE} → {@code BUSY}
 * operator claim transitions.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "voicepulse.routing.redis",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class RedisOperatorPresenceAdapter implements OperatorPresencePort {

    private static final String SCRIPT_SUCCESS_FLAG = "1";
    private static final String SCRIPT_ABSENT_FLAG = "0";

    private final @NonNull ReactiveStringRedisTemplate redisTemplate;
    private final @NonNull RedisKeySchema keySchema;
    private final @NonNull RedisLuaScripts luaScripts;
    private final @NonNull RedisRoutingMapper mapper;
    private final @NonNull RoutingProperties routingProperties;
    private final @NonNull RedisReactiveExecutor reactiveExecutor;

    @Override
    public void savePresence(OperatorPresence presence) {
        requirePresence(presence);
        reactiveExecutor.awaitVoid("savePresence", savePresenceReactive(presence));
    }

    @Override
    public Optional<OperatorPresence> findPresence(OperatorId operatorId) {
        requireOperatorId(operatorId);
        return reactiveExecutor.await("findPresence", findPresenceReactive(operatorId));
    }

    @Override
    public List<OperatorPresence> findAvailableBySkillGroup(SkillGroup skillGroup) {
        requireSkillGroup(skillGroup);
        return reactiveExecutor.await(
                "findAvailableBySkillGroup", findAvailableBySkillGroupReactive(skillGroup));
    }

    @Override
    public List<OperatorPresence> findBySkillGroup(SkillGroup skillGroup) {
        requireSkillGroup(skillGroup);
        return reactiveExecutor.await("findBySkillGroup", findBySkillGroupReactive(skillGroup));
    }

    @Override
    public boolean tryClaimOperator(OperatorId operatorId, CallId callId, Instant claimedAt) {
        requireClaimArgs(operatorId, callId, claimedAt);
        Boolean claimed =
                reactiveExecutor.await(
                        "tryClaimOperator",
                        tryClaimOperatorReactive(operatorId, callId, claimedAt));
        return Boolean.TRUE.equals(claimed);
    }

    @Override
    public void releaseOperator(
            OperatorId operatorId, OperatorAvailabilityStatus nextStatus, Instant releasedAt) {
        requireReleaseArgs(operatorId, nextStatus, releasedAt);
        reactiveExecutor.awaitVoid(
                "releaseOperator", releaseOperatorReactive(operatorId, nextStatus, releasedAt));
    }

    @Override
    public boolean refreshHeartbeat(OperatorId operatorId, Instant heartbeatAt) {
        requireHeartbeatArgs(operatorId, heartbeatAt);
        Boolean refreshed =
                reactiveExecutor.await(
                        "refreshHeartbeat", refreshHeartbeatReactive(operatorId, heartbeatAt));
        return Boolean.TRUE.equals(refreshed);
    }

    /** Reactively saves or updates operator presence, heartbeat TTL, and skill-group sets. */
    public Mono<Void> savePresenceReactive(OperatorPresence presence) {
        requirePresence(presence);
        OperatorPresenceHash hash = mapper.toPresenceHash(presence);
        List<SkillGroup> allSkills = keySchema.allSkillGroups();
        List<String> keys = buildSavePresenceKeys(presence.operatorId());
        List<String> args = buildSavePresenceArgs(presence, hash, allSkills);

        return redisTemplate
                .execute(luaScripts.saveOperatorPresenceScript(), keys, args)
                .next()
                .doOnSuccess(
                        ignored ->
                                log.debug(
                                        "Saved operator presence in Redis: operatorId={},"
                                                + " status={}, skills={}",
                                        presence.operatorId(),
                                        presence.status(),
                                        presence.skills()))
                .then();
    }

    /** Reactively retrieves an operator's presence if its heartbeat key is still active. */
    public Mono<Optional<OperatorPresence>> findPresenceReactive(OperatorId operatorId) {
        requireOperatorId(operatorId);
        String presenceKey = keySchema.operatorPresenceKey(operatorId);
        String heartbeatKey = keySchema.operatorHeartbeatKey(operatorId);

        Mono<Boolean> hasHeartbeat = redisTemplate.hasKey(heartbeatKey).defaultIfEmpty(false);
        Mono<Map<String, String>> entries =
                redisTemplate
                        .<String, String>opsForHash()
                        .entries(presenceKey)
                        .collectMap(Map.Entry::getKey, Map.Entry::getValue);

        return Mono.zip(hasHeartbeat, entries)
                .flatMap(
                        tuple -> {
                            boolean active = Boolean.TRUE.equals(tuple.getT1());
                            Map<String, String> hashEntries = tuple.getT2();
                            if (!active) {
                                if (hashEntries.isEmpty()) {
                                    return Mono.just(Optional.empty());
                                }
                                return evictExpiredOperator(operatorId)
                                        .thenReturn(Optional.empty());
                            }
                            return Mono.just(mapper.fromRedisHashEntries(hashEntries));
                        });
    }

    /** Reactively retrieves all active {@code AVAILABLE} operators for a skill group. */
    public Mono<List<OperatorPresence>> findAvailableBySkillGroupReactive(SkillGroup skillGroup) {
        requireSkillGroup(skillGroup);
        String availableSetKey = keySchema.skillAvailableKey(skillGroup);

        return redisTemplate
                .opsForSet()
                .members(availableSetKey)
                .flatMapSequential(
                        member -> resolveAvailableMember(skillGroup, availableSetKey, member))
                .collectList()
                .map(List::copyOf);
    }

    /** Reactively retrieves all active operators registered for a skill group. */
    public Mono<List<OperatorPresence>> findBySkillGroupReactive(SkillGroup skillGroup) {
        requireSkillGroup(skillGroup);
        String operatorsSetKey = keySchema.skillOperatorsKey(skillGroup);

        return redisTemplate
                .opsForSet()
                .members(operatorsSetKey)
                .flatMapSequential(
                        member -> resolveRegisteredMember(skillGroup, operatorsSetKey, member))
                .collectList()
                .map(List::copyOf);
    }

    /** Reactively executes an atomic {@code AVAILABLE} → {@code BUSY} operator claim. */
    public Mono<Boolean> tryClaimOperatorReactive(
            OperatorId operatorId, CallId callId, Instant claimedAt) {
        requireClaimArgs(operatorId, callId, claimedAt);

        List<String> keys = new ArrayList<>();
        keys.add(keySchema.operatorPresenceKey(operatorId));
        keys.add(keySchema.operatorHeartbeatKey(operatorId));
        keys.addAll(keySchema.allSkillAvailableKeys());

        List<String> args =
                List.of(
                        operatorId.value(),
                        callId.toString(),
                        claimedAt.toString(),
                        ttlMillisString());

        return redisTemplate
                .execute(luaScripts.claimOperatorScript(), keys, args)
                .next()
                .map(result -> Long.valueOf(1L).equals(result))
                .defaultIfEmpty(false)
                .doOnSuccess(
                        claimed ->
                                log.debug(
                                        "Atomic operator claim attempt: operatorId={}, callId={},"
                                                + " claimed={}",
                                        operatorId,
                                        callId,
                                        claimed));
    }

    /** Reactively releases an operator's active call and updates its availability state. */
    public Mono<Void> releaseOperatorReactive(
            OperatorId operatorId, OperatorAvailabilityStatus nextStatus, Instant releasedAt) {
        requireReleaseArgs(operatorId, nextStatus, releasedAt);

        List<String> keys = new ArrayList<>();
        keys.add(keySchema.operatorPresenceKey(operatorId));
        keys.add(keySchema.operatorHeartbeatKey(operatorId));
        keys.addAll(keySchema.allSkillAvailableKeys());

        List<String> args = new ArrayList<>();
        args.add(operatorId.value());
        args.add(nextStatus.name());
        args.add(releasedAt.toString());
        args.add(ttlMillisString());
        for (SkillGroup skill : keySchema.allSkillGroups()) {
            args.add(skill.name());
        }

        return redisTemplate
                .execute(luaScripts.releaseOperatorScript(), keys, args)
                .next()
                .doOnSuccess(
                        released ->
                                log.debug(
                                        "Released operator presence: operatorId={}, nextStatus={},"
                                                + " updated={}",
                                        operatorId,
                                        nextStatus,
                                        Long.valueOf(1L).equals(released)))
                .then();
    }

    /** Reactively refreshes an active operator's heartbeat TTL. */
    public Mono<Boolean> refreshHeartbeatReactive(OperatorId operatorId, Instant heartbeatAt) {
        requireHeartbeatArgs(operatorId, heartbeatAt);

        List<String> keys =
                List.of(
                        keySchema.operatorPresenceKey(operatorId),
                        keySchema.operatorHeartbeatKey(operatorId));
        List<String> args = List.of(heartbeatAt.toString(), ttlMillisString());

        return redisTemplate
                .execute(luaScripts.refreshHeartbeatScript(), keys, args)
                .next()
                .map(result -> Long.valueOf(1L).equals(result))
                .defaultIfEmpty(false);
    }

    private Mono<OperatorPresence> resolveAvailableMember(
            SkillGroup skillGroup, String availableSetKey, String member) {
        OperatorId operatorId = OperatorId.fromString(member);
        return findPresenceReactive(operatorId)
                .flatMap(
                        optionalPresence -> {
                            if (optionalPresence.isEmpty()) {
                                return pruneSkillSets(skillGroup, member, true).then(Mono.empty());
                            }
                            OperatorPresence presence = optionalPresence.get();
                            if (!presence.status().canAcceptCall()
                                    || !presence.skills().contains(skillGroup)) {
                                return redisTemplate
                                        .opsForSet()
                                        .remove(availableSetKey, member)
                                        .then(Mono.empty());
                            }
                            return Mono.just(presence);
                        });
    }

    private Mono<OperatorPresence> resolveRegisteredMember(
            SkillGroup skillGroup, String operatorsSetKey, String member) {
        OperatorId operatorId = OperatorId.fromString(member);
        return findPresenceReactive(operatorId)
                .flatMap(
                        optionalPresence -> {
                            if (optionalPresence.isEmpty()) {
                                return pruneSkillSets(skillGroup, member, true).then(Mono.empty());
                            }
                            OperatorPresence presence = optionalPresence.get();
                            if (!presence.skills().contains(skillGroup)) {
                                return redisTemplate
                                        .opsForSet()
                                        .remove(operatorsSetKey, member)
                                        .then(Mono.empty());
                            }
                            return Mono.just(presence);
                        });
    }

    private Mono<Void> pruneSkillSets(
            SkillGroup skillGroup, String member, boolean pruneOperators) {
        Mono<Long> removeAvailable =
                redisTemplate.opsForSet().remove(keySchema.skillAvailableKey(skillGroup), member);
        if (!pruneOperators) {
            return removeAvailable.then();
        }
        Mono<Long> removeOperators =
                redisTemplate.opsForSet().remove(keySchema.skillOperatorsKey(skillGroup), member);
        return Mono.when(removeAvailable, removeOperators);
    }

    private Mono<Void> evictExpiredOperator(OperatorId operatorId) {
        String member = operatorId.value();
        List<Mono<Long>> removals = new ArrayList<>();
        removals.add(redisTemplate.delete(keySchema.operatorPresenceKey(operatorId)));
        for (String availableKey : keySchema.allSkillAvailableKeys()) {
            removals.add(redisTemplate.opsForSet().remove(availableKey, member));
        }
        for (String operatorsKey : keySchema.allSkillOperatorsKeys()) {
            removals.add(redisTemplate.opsForSet().remove(operatorsKey, member));
        }
        return Mono.when(removals);
    }

    private List<String> buildSavePresenceKeys(OperatorId operatorId) {
        List<String> keys = new ArrayList<>();
        keys.add(keySchema.operatorPresenceKey(operatorId));
        keys.add(keySchema.operatorHeartbeatKey(operatorId));
        keys.addAll(keySchema.allSkillOperatorsKeys());
        keys.addAll(keySchema.allSkillAvailableKeys());
        return keys;
    }

    private List<String> buildSavePresenceArgs(
            OperatorPresence presence, OperatorPresenceHash hash, List<SkillGroup> allSkills) {
        List<String> args = new ArrayList<>();
        args.add(hash.operatorId());
        args.add(hash.displayName());
        args.add(hash.status());
        args.add(hash.skills());
        args.add(hash.activeCallId());
        args.add(hash.lastHeartbeatAt());
        args.add(ttlMillisString());
        args.add(presence.status().canAcceptCall() ? SCRIPT_SUCCESS_FLAG : SCRIPT_ABSENT_FLAG);
        args.add(String.valueOf(allSkills.size()));
        for (SkillGroup skill : allSkills) {
            args.add(presence.skills().contains(skill) ? SCRIPT_SUCCESS_FLAG : SCRIPT_ABSENT_FLAG);
        }
        return args;
    }

    private String ttlMillisString() {
        return String.valueOf(routingProperties.operatorHeartbeatTtl().toMillis());
    }

    private static void requirePresence(OperatorPresence presence) {
        Objects.requireNonNull(presence, "presence must not be null");
    }

    private static void requireOperatorId(OperatorId operatorId) {
        Objects.requireNonNull(operatorId, "operatorId must not be null");
    }

    private static void requireSkillGroup(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");
    }

    private static void requireClaimArgs(OperatorId operatorId, CallId callId, Instant claimedAt) {
        requireOperatorId(operatorId);
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(claimedAt, "claimedAt must not be null");
    }

    private static void requireReleaseArgs(
            OperatorId operatorId, OperatorAvailabilityStatus nextStatus, Instant releasedAt) {
        requireOperatorId(operatorId);
        Objects.requireNonNull(nextStatus, "nextStatus must not be null");
        Objects.requireNonNull(releasedAt, "releasedAt must not be null");
    }

    private static void requireHeartbeatArgs(OperatorId operatorId, Instant heartbeatAt) {
        requireOperatorId(operatorId);
        Objects.requireNonNull(heartbeatAt, "heartbeatAt must not be null");
    }
}
