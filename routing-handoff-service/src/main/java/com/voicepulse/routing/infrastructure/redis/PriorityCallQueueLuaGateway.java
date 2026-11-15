package com.voicepulse.routing.infrastructure.redis;

import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import com.voicepulse.routing.infrastructure.redis.model.QueuedTicketRedisPayload;
import com.voicepulse.shared.model.CallId;
import java.nio.ByteBuffer;
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
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisElementReader;
import org.springframework.data.redis.serializer.RedisElementWriter;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Typed Lua script gateway encapsulating {@code KEYS}/{@code ARGV} binding and Spring Data {@link
 * RedisSerializationContext}-backed JSON serialization for priority call queue operations.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "voicepulse.routing.redis",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class PriorityCallQueueLuaGateway {

    private final @NonNull ReactiveStringRedisTemplate stringRedisTemplate;
    private final @NonNull ReactiveRedisTemplate<String, QueuedTicketRedisPayload>
            queuedTicketRedisTemplate;
    private final @NonNull RedisKeySchema keySchema;
    private final @NonNull RedisLuaScripts luaScripts;
    private final @NonNull RoutingProperties routingProperties;

    /** Atomically enqueues or reprioritizes a ticket payload and returns its 1-based position. */
    public Mono<Integer> enqueue(
            QueuedTicketRedisPayload payload,
            CallId callId,
            SkillGroup skillGroup,
            int priorityScore,
            Instant enqueuedAt) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");

        String payloadJson = encodePayload(payload, callId);
        List<SkillGroup> allSkills = keySchema.allSkillGroups();
        List<String> keys = new ArrayList<>(4 + allSkills.size());
        keys.add(keySchema.queueKey(skillGroup));
        keys.add(keySchema.queueCallIndexKey());
        keys.add(keySchema.queueItemsKey());
        keys.add(keySchema.queueSequenceKey());
        keys.addAll(keySchema.allQueueKeys());

        List<String> args = new ArrayList<>(6 + allSkills.size());
        args.add(callId.toString());
        args.add(skillGroup.name());
        args.add(String.valueOf(-((long) priorityScore)));
        args.add(formatTimestampPrefix(enqueuedAt));
        args.add(payloadJson);
        args.add(String.valueOf(routingProperties.maxQueueCapacity()));
        for (SkillGroup skill : allSkills) {
            args.add(skill.name());
        }

        return stringRedisTemplate
                .execute(luaScripts.enqueuePriorityCallScript(), keys, args)
                .next()
                .switchIfEmpty(
                        Mono.error(
                                new RedisRoutingException(
                                        "Enqueue Lua script returned no result for callId="
                                                + callId)))
                .flatMap(
                        rank -> {
                            if (rank < 0L) {
                                return Mono.error(
                                        new RedisRoutingException(
                                                "Priority queue capacity exceeded for skillGroup="
                                                        + skillGroup
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
                                        callId,
                                        skillGroup,
                                        priorityScore,
                                        pos));
    }

    /** Atomically pops the highest-priority ticket payload for a skill group. */
    public Mono<Optional<QueuedTicketRedisPayload>> dequeueHighestPriority(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");

        List<String> keys =
                List.of(
                        keySchema.queueKey(skillGroup),
                        keySchema.queueCallIndexKey(),
                        keySchema.queueItemsKey());

        RedisSerializationContext<String, QueuedTicketRedisPayload> context =
                queuedTicketRedisTemplate.getSerializationContext();
        RedisElementWriter<String> argsWriter = context.getStringSerializationPair().getWriter();
        RedisElementReader<QueuedTicketRedisPayload> resultReader =
                context.getValueSerializationPair().getReader();

        return queuedTicketRedisTemplate
                .execute(
                        luaScripts.dequeuePriorityCallScript(),
                        keys,
                        List.of(),
                        argsWriter,
                        resultReader)
                .next()
                .onErrorMap(
                        SerializationException.class,
                        ex ->
                                new RedisRoutingException(
                                        "Failed to deserialize QueuedTicketRedisPayload", ex))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    /** Atomically removes a queued ticket payload by {@link CallId} across all skill queues. */
    public Mono<Optional<QueuedTicketRedisPayload>> removeByCallId(CallId callId) {
        Objects.requireNonNull(callId, "callId must not be null");

        List<SkillGroup> allSkills = keySchema.allSkillGroups();
        List<String> keys = new ArrayList<>(2 + allSkills.size());
        keys.add(keySchema.queueCallIndexKey());
        keys.add(keySchema.queueItemsKey());
        keys.addAll(keySchema.allQueueKeys());

        List<String> args = new ArrayList<>(1 + allSkills.size());
        args.add(callId.toString());
        for (SkillGroup skill : allSkills) {
            args.add(skill.name());
        }

        RedisSerializationContext<String, QueuedTicketRedisPayload> context =
                queuedTicketRedisTemplate.getSerializationContext();
        RedisElementWriter<String> argsWriter = context.getStringSerializationPair().getWriter();
        RedisElementReader<QueuedTicketRedisPayload> resultReader =
                context.getValueSerializationPair().getReader();

        return queuedTicketRedisTemplate
                .execute(
                        luaScripts.removePriorityCallScript(), keys, args, argsWriter, resultReader)
                .next()
                .onErrorMap(
                        SerializationException.class,
                        ex ->
                                new RedisRoutingException(
                                        "Failed to deserialize QueuedTicketRedisPayload", ex))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    static String formatTimestampPrefix(Instant enqueuedAt) {
        long biasedEpochSeconds = enqueuedAt.getEpochSecond() ^ Long.MIN_VALUE;
        return String.format(Locale.ROOT, "%016x:%09d", biasedEpochSeconds, enqueuedAt.getNano());
    }

    private String encodePayload(QueuedTicketRedisPayload payload, CallId callId) {
        RedisSerializationContext<String, QueuedTicketRedisPayload> context =
                queuedTicketRedisTemplate.getSerializationContext();
        try {
            ByteBuffer encoded = context.getValueSerializationPair().write(payload);
            return context.getStringSerializationPair().read(encoded);
        } catch (SerializationException ex) {
            throw new RedisRoutingException(
                    "Failed to serialize QueuedTicketRedisPayload for callId=" + callId, ex);
        }
    }
}
