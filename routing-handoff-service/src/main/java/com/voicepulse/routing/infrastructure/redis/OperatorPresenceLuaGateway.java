package com.voicepulse.routing.infrastructure.redis;

import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceHash;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Typed Lua script gateway encapsulating positional {@code KEYS} and {@code ARGV} binding for
 * atomic operator presence mutations, claims, heartbeat renewals, and batch evictions.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "voicepulse.routing.redis",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class OperatorPresenceLuaGateway {

    private static final String SCRIPT_SUCCESS_FLAG = "1";
    private static final String SCRIPT_ABSENT_FLAG = "0";

    private final @NonNull ReactiveStringRedisTemplate stringRedisTemplate;
    private final @NonNull RedisKeySchema keySchema;
    private final @NonNull RedisLuaScripts luaScripts;
    private final @NonNull RoutingProperties routingProperties;

    /** Atomically writes the operator presence hash, heartbeat TTL key, and skill-group sets. */
    public Mono<Void> savePresence(
            OperatorId operatorId,
            OperatorPresenceHash hash,
            Set<SkillGroup> skills,
            boolean available) {
        requireOperatorId(operatorId);
        Objects.requireNonNull(hash, "hash must not be null");
        Objects.requireNonNull(skills, "skills must not be null");

        List<SkillGroup> allSkills = keySchema.allSkillGroups();
        List<String> keys = new ArrayList<>(2 + allSkills.size() * 2);
        keys.add(keySchema.operatorPresenceKey(operatorId));
        keys.add(keySchema.operatorHeartbeatKey(operatorId));
        keys.addAll(keySchema.allSkillOperatorsKeys());
        keys.addAll(keySchema.allSkillAvailableKeys());

        List<String> args = new ArrayList<>(9 + allSkills.size());
        args.add(hash.operatorId());
        args.add(hash.displayName());
        args.add(hash.status());
        args.add(hash.skills());
        args.add(hash.activeCallId());
        args.add(hash.lastHeartbeatAt());
        args.add(ttlMillisString());
        args.add(available ? SCRIPT_SUCCESS_FLAG : SCRIPT_ABSENT_FLAG);
        args.add(String.valueOf(allSkills.size()));
        for (SkillGroup skill : allSkills) {
            args.add(skills.contains(skill) ? SCRIPT_SUCCESS_FLAG : SCRIPT_ABSENT_FLAG);
        }

        return stringRedisTemplate
                .execute(luaScripts.saveOperatorPresenceScript(), keys, args)
                .next()
                .doOnSuccess(
                        ignored ->
                                log.debug(
                                        "Saved operator presence in Redis: operatorId={},"
                                                + " status={}, skills={}",
                                        operatorId,
                                        hash.status(),
                                        skills))
                .then();
    }

    /** Atomically transitions an {@code AVAILABLE} live operator to {@code BUSY} for a call. */
    public Mono<Boolean> tryClaimOperator(OperatorId operatorId, CallId callId, Instant claimedAt) {
        requireOperatorId(operatorId);
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(claimedAt, "claimedAt must not be null");

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

        return stringRedisTemplate
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

    /** Atomically releases an active operator's call binding and updates skill availability. */
    public Mono<Void> releaseOperator(
            OperatorId operatorId, OperatorAvailabilityStatus nextStatus, Instant releasedAt) {
        requireOperatorId(operatorId);
        Objects.requireNonNull(nextStatus, "nextStatus must not be null");
        Objects.requireNonNull(releasedAt, "releasedAt must not be null");

        List<SkillGroup> allSkills = keySchema.allSkillGroups();
        List<String> keys = new ArrayList<>(2 + allSkills.size());
        keys.add(keySchema.operatorPresenceKey(operatorId));
        keys.add(keySchema.operatorHeartbeatKey(operatorId));
        keys.addAll(keySchema.allSkillAvailableKeys());

        List<String> args = new ArrayList<>(4 + allSkills.size());
        args.add(operatorId.value());
        args.add(nextStatus.name());
        args.add(releasedAt.toString());
        args.add(ttlMillisString());
        for (SkillGroup skill : allSkills) {
            args.add(skill.name());
        }

        return stringRedisTemplate
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

    /** Atomically extends both presence hash and heartbeat liveness TTLs. */
    public Mono<Boolean> refreshHeartbeat(OperatorId operatorId, Instant heartbeatAt) {
        requireOperatorId(operatorId);
        Objects.requireNonNull(heartbeatAt, "heartbeatAt must not be null");

        List<String> keys =
                List.of(
                        keySchema.operatorPresenceKey(operatorId),
                        keySchema.operatorHeartbeatKey(operatorId));
        List<String> args = List.of(heartbeatAt.toString(), ttlMillisString());

        return stringRedisTemplate
                .execute(luaScripts.refreshHeartbeatScript(), keys, args)
                .next()
                .map(result -> Long.valueOf(1L).equals(result))
                .defaultIfEmpty(false);
    }

    /**
     * Atomically deletes expired presence hashes and removes the expired operators from all skill
     * availability and membership sets in a single Redis round-trip.
     */
    public Mono<Long> evictExpiredOperators(List<OperatorId> expiredOperatorIds) {
        Objects.requireNonNull(expiredOperatorIds, "expiredOperatorIds must not be null");
        if (expiredOperatorIds.isEmpty()) {
            return Mono.just(0L);
        }

        List<OperatorId> distinctExpired = expiredOperatorIds.stream().distinct().toList();
        List<String> availableKeys = keySchema.allSkillAvailableKeys();
        List<String> operatorsKeys = keySchema.allSkillOperatorsKeys();

        List<String> keys =
                new ArrayList<>(
                        distinctExpired.size() + availableKeys.size() + operatorsKeys.size());
        for (OperatorId operatorId : distinctExpired) {
            keys.add(keySchema.operatorPresenceKey(operatorId));
        }
        keys.addAll(availableKeys);
        keys.addAll(operatorsKeys);

        List<String> args = new ArrayList<>(1 + distinctExpired.size());
        args.add(String.valueOf(distinctExpired.size()));
        for (OperatorId operatorId : distinctExpired) {
            args.add(operatorId.value());
        }

        return stringRedisTemplate
                .execute(luaScripts.evictExpiredOperatorsScript(), keys, args)
                .next()
                .defaultIfEmpty(0L)
                .doOnSuccess(
                        removed ->
                                log.debug(
                                        "Evicted expired operators atomically: count={},"
                                                + " removedEntries={}",
                                        distinctExpired.size(),
                                        removed));
    }

    private static void requireOperatorId(OperatorId operatorId) {
        Objects.requireNonNull(operatorId, "operatorId must not be null");
    }

    private String ttlMillisString() {
        return String.valueOf(routingProperties.operatorHeartbeatTtl().toMillis());
    }
}
