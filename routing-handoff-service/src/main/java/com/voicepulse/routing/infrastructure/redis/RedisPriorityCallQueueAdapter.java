package com.voicepulse.routing.infrastructure.redis;

import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.routing.infrastructure.redis.mapper.RedisRoutingMapper;
import com.voicepulse.routing.infrastructure.redis.model.QueuedTicketRedisPayload;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reactive Redis 7 outbound adapter implementing {@link PriorityCallQueuePort}.
 *
 * <p>Uses Redis Sorted Sets ({@code ZSET}) keyed per {@link SkillGroup} with negative integer
 * priority scores ({@code -priorityScore}) and typed Spring Data {@link ReactiveRedisTemplate}
 * serialization via {@link PriorityCallQueueLuaGateway}.
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

    private final @NonNull ReactiveStringRedisTemplate stringRedisTemplate;
    private final @NonNull ReactiveRedisTemplate<String, QueuedTicketRedisPayload>
            queuedTicketRedisTemplate;
    private final @NonNull RedisKeySchema keySchema;
    private final @NonNull PriorityCallQueueLuaGateway luaGateway;
    private final @NonNull RedisRoutingMapper mapper;
    private final @NonNull RedisReactiveExecutor reactiveExecutor;

    @Override
    public int enqueue(QueuedTicketItem item) {
        requireItem(item);
        Integer position = reactiveExecutor.await("enqueue", enqueueReactive(item));
        return position == null ? 0 : position;
    }

    @Override
    public Optional<QueuedTicketItem> dequeueHighestPriority(SkillGroup skillGroup) {
        requireSkillGroup(skillGroup);
        return reactiveExecutor.await(
                "dequeueHighestPriority", dequeueHighestPriorityReactive(skillGroup));
    }

    @Override
    public Optional<QueuedTicketItem> removeByCallId(CallId callId) {
        requireCallId(callId);
        return reactiveExecutor.await("removeByCallId", removeByCallIdReactive(callId));
    }

    @Override
    public List<QueuedTicketItem> listQueuedBySkillGroup(SkillGroup skillGroup) {
        requireSkillGroup(skillGroup);
        return reactiveExecutor.await(
                "listQueuedBySkillGroup", listQueuedBySkillGroupReactive(skillGroup));
    }

    @Override
    public long queueDepth(SkillGroup skillGroup) {
        requireSkillGroup(skillGroup);
        Long depth = reactiveExecutor.await("queueDepth", queueDepthReactive(skillGroup));
        return depth == null ? 0L : depth;
    }

    /** Reactively enqueues a ticket item and returns its 1-based queue position. */
    public Mono<Integer> enqueueReactive(QueuedTicketItem item) {
        requireItem(item);
        QueuedTicketRedisPayload payload = mapper.toRedisPayload(item);
        return luaGateway.enqueue(
                payload, item.callId(), item.skillGroup(), item.priorityScore(), item.enqueuedAt());
    }

    /** Reactively pops the highest-priority (earliest FIFO on ties) item from a queue. */
    public Mono<Optional<QueuedTicketItem>> dequeueHighestPriorityReactive(SkillGroup skillGroup) {
        requireSkillGroup(skillGroup);
        return luaGateway
                .dequeueHighestPriority(skillGroup)
                .map(optional -> optional.map(mapper::toQueuedTicketItem));
    }

    /** Reactively removes a queued call by {@link CallId} across all skill-group queues. */
    public Mono<Optional<QueuedTicketItem>> removeByCallIdReactive(CallId callId) {
        requireCallId(callId);
        return luaGateway
                .removeByCallId(callId)
                .map(optional -> optional.map(mapper::toQueuedTicketItem));
    }

    /** Reactively lists all queued items for a skill group in exact priority-FIFO dequeue order. */
    public Mono<List<QueuedTicketItem>> listQueuedBySkillGroupReactive(SkillGroup skillGroup) {
        requireSkillGroup(skillGroup);
        return stringRedisTemplate
                .opsForZSet()
                .range(keySchema.queueKey(skillGroup), Range.unbounded())
                .map(RedisPriorityCallQueueAdapter::extractCallIdFromMember)
                .collectList()
                .flatMap(this::loadQueuedItemsInOrder);
    }

    /** Reactively returns the current number of queued calls in a skill-group queue. */
    public Mono<Long> queueDepthReactive(SkillGroup skillGroup) {
        requireSkillGroup(skillGroup);
        return stringRedisTemplate
                .opsForZSet()
                .size(keySchema.queueKey(skillGroup))
                .defaultIfEmpty(0L);
    }

    private Mono<List<QueuedTicketItem>> loadQueuedItemsInOrder(List<String> callIds) {
        if (callIds.isEmpty()) {
            return Mono.just(List.of());
        }
        return queuedTicketRedisTemplate
                .<String, QueuedTicketRedisPayload>opsForHash()
                .multiGet(keySchema.queueItemsKey(), callIds)
                .map(
                        payloads ->
                                payloads.stream()
                                        .filter(Objects::nonNull)
                                        .map(mapper::toQueuedTicketItem)
                                        .toList())
                .defaultIfEmpty(List.of());
    }

    static String formatTimestampPrefix(Instant enqueuedAt) {
        return PriorityCallQueueLuaGateway.formatTimestampPrefix(enqueuedAt);
    }

    static String extractCallIdFromMember(String member) {
        int lastColon = member.lastIndexOf(':');
        if (lastColon < 0 || lastColon == member.length() - 1) {
            throw new RedisRoutingException("Malformed priority queue ZSET member: " + member);
        }
        return member.substring(lastColon + 1);
    }

    private static void requireItem(QueuedTicketItem item) {
        Objects.requireNonNull(item, "item must not be null");
    }

    private static void requireSkillGroup(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");
    }

    private static void requireCallId(CallId callId) {
        Objects.requireNonNull(callId, "callId must not be null");
    }
}
