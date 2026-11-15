package com.voicepulse.routing.infrastructure.redis;

import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.infrastructure.redis.mapper.OperatorPresenceHashMapper;
import com.voicepulse.routing.infrastructure.redis.mapper.RedisRoutingMapper;
import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceHash;
import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceReadSnapshot;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
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
 * <p>Coordinates pipelined presence batch reads, declarative {@link OperatorPresenceHashMapper}
 * hash decoding, and atomic {@link OperatorPresenceLuaGateway} state transitions.
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

    private final @NonNull ReactiveStringRedisTemplate redisTemplate;
    private final @NonNull RedisKeySchema keySchema;
    private final @NonNull OperatorPresenceBatchReader batchReader;
    private final @NonNull OperatorPresenceLuaGateway luaGateway;
    private final @NonNull OperatorPresenceHashMapper hashMapper;
    private final @NonNull RedisRoutingMapper mapper;
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
        return luaGateway.savePresence(
                presence.operatorId(), hash, presence.skills(), presence.status().canAcceptCall());
    }

    /** Reactively retrieves an operator's presence if its heartbeat key is still active. */
    public Mono<Optional<OperatorPresence>> findPresenceReactive(OperatorId operatorId) {
        requireOperatorId(operatorId);
        return batchReader
                .readOperatorPresences(List.of(operatorId), true)
                .flatMap(
                        snapshots -> {
                            if (snapshots.isEmpty()) {
                                return Mono.just(Optional.empty());
                            }
                            OperatorPresenceReadSnapshot snapshot = snapshots.getFirst();
                            if (!snapshot.heartbeatActive()) {
                                if (snapshot.hashEntries().isEmpty()) {
                                    return Mono.just(Optional.empty());
                                }
                                return luaGateway
                                        .evictExpiredOperators(List.of(operatorId))
                                        .thenReturn(Optional.empty());
                            }
                            return Mono.just(decodePresence(snapshot));
                        });
    }

    /** Reactively retrieves all active {@code AVAILABLE} operators for a skill group. */
    public Mono<List<OperatorPresence>> findAvailableBySkillGroupReactive(SkillGroup skillGroup) {
        requireSkillGroup(skillGroup);
        return findSkillGroupPresences(
                keySchema.skillAvailableKey(skillGroup),
                presence ->
                        presence.status().canAcceptCall()
                                && presence.skills().contains(skillGroup));
    }

    /** Reactively retrieves all active operators registered for a skill group. */
    public Mono<List<OperatorPresence>> findBySkillGroupReactive(SkillGroup skillGroup) {
        requireSkillGroup(skillGroup);
        return findSkillGroupPresences(
                keySchema.skillOperatorsKey(skillGroup),
                presence -> presence.skills().contains(skillGroup));
    }

    /** Reactively executes an atomic {@code AVAILABLE} → {@code BUSY} operator claim. */
    public Mono<Boolean> tryClaimOperatorReactive(
            OperatorId operatorId, CallId callId, Instant claimedAt) {
        requireClaimArgs(operatorId, callId, claimedAt);
        return luaGateway.tryClaimOperator(operatorId, callId, claimedAt);
    }

    /** Reactively releases an operator's active call and updates its availability state. */
    public Mono<Void> releaseOperatorReactive(
            OperatorId operatorId, OperatorAvailabilityStatus nextStatus, Instant releasedAt) {
        requireReleaseArgs(operatorId, nextStatus, releasedAt);
        return luaGateway.releaseOperator(operatorId, nextStatus, releasedAt);
    }

    /** Reactively refreshes an active operator's heartbeat TTL. */
    public Mono<Boolean> refreshHeartbeatReactive(OperatorId operatorId, Instant heartbeatAt) {
        requireHeartbeatArgs(operatorId, heartbeatAt);
        return luaGateway.refreshHeartbeat(operatorId, heartbeatAt);
    }

    private Mono<List<OperatorPresence>> findSkillGroupPresences(
            String setKey, Predicate<OperatorPresence> inclusionPredicate) {
        return redisTemplate
                .opsForSet()
                .members(setKey)
                .collectList()
                .flatMap(
                        members -> {
                            if (members.isEmpty()) {
                                return Mono.just(List.of());
                            }
                            return resolveSkillGroupSnapshots(setKey, members, inclusionPredicate);
                        });
    }

    private Mono<List<OperatorPresence>> resolveSkillGroupSnapshots(
            String setKey, List<String> members, Predicate<OperatorPresence> inclusionPredicate) {
        List<OperatorId> validIds = new ArrayList<>(members.size());
        List<String> staleMembers = new ArrayList<>();
        for (String member : members) {
            try {
                validIds.add(OperatorId.fromString(member));
            } catch (IllegalArgumentException ex) {
                log.warn(
                        "Pruning malformed operator member from skill set: setKey={}, member={}",
                        setKey,
                        member);
                staleMembers.add(member);
            }
        }
        if (validIds.isEmpty()) {
            return pruneStaleSetMembers(setKey, staleMembers).thenReturn(List.of());
        }
        return batchReader
                .readOperatorPresences(validIds, false)
                .flatMap(
                        snapshots ->
                                partitionAndCleanSnapshots(
                                        setKey, snapshots, staleMembers, inclusionPredicate));
    }

    private Mono<List<OperatorPresence>> partitionAndCleanSnapshots(
            String setKey,
            List<OperatorPresenceReadSnapshot> snapshots,
            List<String> initialStaleMembers,
            Predicate<OperatorPresence> inclusionPredicate) {
        List<OperatorPresence> validPresences = new ArrayList<>(snapshots.size());
        List<OperatorId> expiredOperatorIds = new ArrayList<>();
        List<String> staleSetMembers = new ArrayList<>(initialStaleMembers);

        for (OperatorPresenceReadSnapshot snapshot : snapshots) {
            if (!snapshot.heartbeatActive()) {
                expiredOperatorIds.add(snapshot.operatorId());
                continue;
            }
            Optional<OperatorPresence> decoded = decodePresence(snapshot);
            if (decoded.isPresent() && inclusionPredicate.test(decoded.get())) {
                validPresences.add(decoded.get());
            } else {
                staleSetMembers.add(snapshot.operatorId().value());
            }
        }

        return Mono.when(
                        luaGateway.evictExpiredOperators(expiredOperatorIds),
                        pruneStaleSetMembers(setKey, staleSetMembers))
                .thenReturn(List.copyOf(validPresences));
    }

    private Mono<Void> pruneStaleSetMembers(String setKey, List<String> staleMembers) {
        if (staleMembers.isEmpty()) {
            return Mono.empty();
        }
        return redisTemplate
                .opsForSet()
                .remove(setKey, staleMembers.toArray())
                .doOnSuccess(
                        removed ->
                                log.debug(
                                        "Pruned stale skill set members: setKey={}, count={},"
                                                + " removed={}",
                                        setKey,
                                        staleMembers.size(),
                                        removed))
                .then();
    }

    private Optional<OperatorPresence> decodePresence(OperatorPresenceReadSnapshot snapshot) {
        return hashMapper.tryFromHash(snapshot.hashEntries()).map(mapper::toOperatorPresence);
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
