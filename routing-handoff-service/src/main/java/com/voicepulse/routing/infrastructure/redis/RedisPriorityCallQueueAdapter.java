package com.voicepulse.routing.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import com.voicepulse.routing.infrastructure.redis.mapper.RedisRoutingMapper;
import com.voicepulse.routing.infrastructure.redis.model.QueuedTicketRedisPayload;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reactive Redis 7 outbound adapter implementing {@link PriorityCallQueuePort}.
 *
 * <p>Uses Redis Sorted Sets ({@code ZSET}) keyed per {@link SkillGroup} with negative integer
 * priority scores ({@code -priorityScore}) and lexicographical timestamp+sequence member keys to
 * guarantee exact priority-first, FIFO-on-tie dequeue order.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "voicepulse.routing.redis",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class RedisPriorityCallQueueAdapter implements PriorityCallQueuePort {

    private final @NonNull ReactiveStringRedisTemplate redisTemplate;
    private final @NonNull RedisKeySchema keySchema;
    private final @NonNull RedisLuaScripts luaScripts;
    private final @NonNull RedisRoutingMapper mapper;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull RoutingProperties routingProperties;
    private final @NonNull RedisReactiveExecutor reactiveExecutor;

    @Override
    public int enqueue(QueuedTicketItem item) {
        Objects.requireNonNull(item, "item must not be null");
        Integer position = reactiveExecutor.await("enqueue", enqueueReactive(item));
        return position == null ? 0 : position;
    }

    @Override
    public Optional<QueuedTicketItem> dequeueHighestPriority(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");
        return reactiveExecutor.await(
                "dequeueHighestPriority", dequeueHighestPriorityReactive(skillGroup));
    }

    @Override
    public Optional<QueuedTicketItem> removeByCallId(CallId callId) {
        Objects.requireNonNull(callId, "callId must not be null");
        return reactiveExecutor.await("removeByCallId", removeByCallIdReactive(callId));
    }

    @Override
    public List<QueuedTicketItem> listQueuedBySkillGroup(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");
        return reactiveExecutor.await(
                "listQueuedBySkillGroup", listQueuedBySkillGroupReactive(skillGroup));
    }

    @Override
    public long queueDepth(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");
        Long depth = reactiveExecutor.await("queueDepth", queueDepthReactive(skillGroup));
        return depth == null ? 0L : depth;
    }

    /** Reactively enqueues a ticket item and returns its 1-based queue position. */
    public Mono<Integer> enqueueReactive(QueuedTicketItem item) {
        Objects.requireNonNull(item, "item must not be null");
        String payloadJson = serializeItem(item);
        List<String> keys = buildEnqueueKeys(item.skillGroup());
        List<String> args = buildEnqueueArgs(item, payloadJson);

        return redisTemplate
                .execute(luaScripts.enqueuePriorityCallScript(), keys, args)
                .next()
                .switchIfEmpty(
                        Mono.error(
                                new RedisRoutingException(
                                        "Enqueue Lua script returned no result for callId="
                                                + item.callId())))
                .flatMap(
                        rank -> {
                            if (rank < 0L) {
                                return Mono.error(
                                        new RedisRoutingException(
                                                "Priority queue capacity exceeded for skillGroup="
                                                        + item.skillGroup()
                                                        + ", maxQueueCapacity="
                                                        + routingProperties.maxQueueCapacity()));
                            }
                            return Mono.just(Math.toIntExact(rank));
                        })
                .doOnSuccess(
                        pos ->
                                log.debug(
                                        "Enqueued call in Redis priority queue: callId={},"
                                                + " skillGroup={}, priorityScore={}, position={}",
                                        item.callId(),
                                        item.skillGroup(),
                                        item.priorityScore(),
                                        pos));
    }

    /** Reactively pops the highest-priority (earliest FIFO on ties) item from a queue. */
    public Mono<Optional<QueuedTicketItem>> dequeueHighestPriorityReactive(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");
        List<String> keys =
                List.of(
                        keySchema.queueKey(skillGroup),
                        keySchema.queueCallIndexKey(),
                        keySchema.queueItemsKey());

        return redisTemplate
                .execute(luaScripts.dequeuePriorityCallScript(), keys, List.of())
                .next()
                .map(this::deserializeItem)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    /** Reactively removes a queued call by {@link CallId} across all skill-group queues. */
    public Mono<Optional<QueuedTicketItem>> removeByCallIdReactive(CallId callId) {
        Objects.requireNonNull(callId, "callId must not be null");
        List<String> keys = new ArrayList<>();
        keys.add(keySchema.queueCallIndexKey());
        keys.add(keySchema.queueItemsKey());
        keys.addAll(keySchema.allQueueKeys());

        List<String> args = new ArrayList<>();
        args.add(callId.toString());
        for (SkillGroup skill : keySchema.allSkillGroups()) {
            args.add(skill.name());
        }

        return redisTemplate
                .execute(luaScripts.removePriorityCallScript(), keys, args)
                .next()
                .map(this::deserializeItem)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    /** Reactively lists all queued items for a skill group in exact priority-FIFO dequeue order. */
    public Mono<List<QueuedTicketItem>> listQueuedBySkillGroupReactive(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");
        return redisTemplate
                .opsForZSet()
                .range(keySchema.queueKey(skillGroup), Range.unbounded())
                .map(RedisPriorityCallQueueAdapter::extractCallIdFromMember)
                .collectList()
                .flatMap(this::loadQueuedItemsInOrder);
    }

    /** Reactively returns the current number of queued calls in a skill-group queue. */
    public Mono<Long> queueDepthReactive(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");
        return redisTemplate.opsForZSet().size(keySchema.queueKey(skillGroup)).defaultIfEmpty(0L);
    }

    private Mono<List<QueuedTicketItem>> loadQueuedItemsInOrder(List<String> callIds) {
        if (callIds.isEmpty()) {
            return Mono.just(List.of());
        }
        return redisTemplate
                .<String, String>opsForHash()
                .multiGet(keySchema.queueItemsKey(), callIds)
                .map(
                        jsonValues ->
                                jsonValues.stream()
                                        .filter(Objects::nonNull)
                                        .map(this::deserializeItem)
                                        .toList())
                .defaultIfEmpty(List.of());
    }

    private List<String> buildEnqueueKeys(SkillGroup skillGroup) {
        List<String> keys = new ArrayList<>();
        keys.add(keySchema.queueKey(skillGroup));
        keys.add(keySchema.queueCallIndexKey());
        keys.add(keySchema.queueItemsKey());
        keys.add(keySchema.queueSequenceKey());
        keys.addAll(keySchema.allQueueKeys());
        return keys;
    }

    private List<String> buildEnqueueArgs(QueuedTicketItem item, String payloadJson) {
        List<String> args = new ArrayList<>();
        args.add(item.callId().toString());
        args.add(item.skillGroup().name());
        args.add(String.valueOf(-((long) item.priorityScore())));
        args.add(formatTimestampPrefix(item.enqueuedAt()));
        args.add(payloadJson);
        args.add(String.valueOf(routingProperties.maxQueueCapacity()));
        for (SkillGroup skill : keySchema.allSkillGroups()) {
            args.add(skill.name());
        }
        return args;
    }

    private String serializeItem(QueuedTicketItem item) {
        try {
            QueuedTicketRedisPayload payload = mapper.toRedisPayload(item);
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new RedisRoutingException(
                    "Failed to serialize QueuedTicketItem for callId=" + item.callId(), ex);
        }
    }

    private QueuedTicketItem deserializeItem(String payloadJson) {
        try {
            QueuedTicketRedisPayload payload =
                    objectMapper.readValue(payloadJson, QueuedTicketRedisPayload.class);
            return mapper.toQueuedTicketItem(payload);
        } catch (JsonProcessingException ex) {
            throw new RedisRoutingException("Failed to deserialize QueuedTicketRedisPayload", ex);
        }
    }

    static String formatTimestampPrefix(Instant enqueuedAt) {
        long biasedEpochSeconds = enqueuedAt.getEpochSecond() ^ Long.MIN_VALUE;
        return String.format(Locale.ROOT, "%016x:%09d", biasedEpochSeconds, enqueuedAt.getNano());
    }

    static String extractCallIdFromMember(String member) {
        int lastColon = member.lastIndexOf(':');
        if (lastColon < 0 || lastColon == member.length() - 1) {
            throw new RedisRoutingException("Malformed priority queue ZSET member: " + member);
        }
        return member.substring(lastColon + 1);
    }
}
