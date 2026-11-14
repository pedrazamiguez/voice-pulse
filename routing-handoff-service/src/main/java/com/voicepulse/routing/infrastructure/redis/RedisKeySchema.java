package com.voicepulse.routing.infrastructure.redis;

import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.redis.config.RedisRoutingProperties;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Canonical Redis 7 key layout generator for operator presence hashes, heartbeat TTL keys,
 * skill-group membership/availability sets, and priority call queues.
 */
@Component
@RequiredArgsConstructor
public class RedisKeySchema {

    private final @NonNull RedisRoutingProperties properties;

    /** Returns the normalized Redis namespace prefix. */
    public String keyPrefix() {
        return properties.keyPrefix();
    }

    /** Returns the Redis HASH key holding an operator's presence state and active call binding. */
    public String operatorPresenceKey(OperatorId operatorId) {
        return properties.keyPrefix()
                + ":operators:"
                + requireOperatorId(operatorId).value()
                + ":presence";
    }

    /** Returns the Redis STRING TTL key tracking an operator's active heartbeat liveness. */
    public String operatorHeartbeatKey(OperatorId operatorId) {
        return properties.keyPrefix()
                + ":operators:"
                + requireOperatorId(operatorId).value()
                + ":heartbeat";
    }

    /** Returns the Redis SET key tracking currently AVAILABLE operators for a skill group. */
    public String skillAvailableKey(SkillGroup skillGroup) {
        return properties.keyPrefix()
                + ":skills:"
                + requireSkillGroup(skillGroup).name()
                + ":available";
    }

    /** Returns the Redis SET key tracking all registered operators for a skill group. */
    public String skillOperatorsKey(SkillGroup skillGroup) {
        return properties.keyPrefix()
                + ":skills:"
                + requireSkillGroup(skillGroup).name()
                + ":operators";
    }

    /** Returns the Redis ZSET key maintaining priority + FIFO queued calls for a skill group. */
    public String queueKey(SkillGroup skillGroup) {
        return properties.keyPrefix() + ":queues:" + requireSkillGroup(skillGroup).name();
    }

    /** Returns the monotonic Redis counter key used to break same-timestamp FIFO ties. */
    public String queueSequenceKey() {
        return properties.keyPrefix() + ":queues:sequence";
    }

    /** Returns the Redis HASH key mapping {@code callId -> "<skillGroup>|<zsetMember>"}. */
    public String queueCallIndexKey() {
        return properties.keyPrefix() + ":queues:call-index";
    }

    /**
     * Returns the Redis HASH key storing serialized queued ticket payloads keyed by {@code callId}.
     */
    public String queueItemsKey() {
        return properties.keyPrefix() + ":queues:items";
    }

    /** Returns all skill groups in deterministic enum declaration order. */
    public List<SkillGroup> allSkillGroups() {
        return List.of(SkillGroup.values());
    }

    /** Returns all skill-group availability SET keys in deterministic {@link SkillGroup} order. */
    public List<String> allSkillAvailableKeys() {
        return Arrays.stream(SkillGroup.values()).map(this::skillAvailableKey).toList();
    }

    /**
     * Returns all skill-group registered operator SET keys in deterministic {@link SkillGroup}
     * order.
     */
    public List<String> allSkillOperatorsKeys() {
        return Arrays.stream(SkillGroup.values()).map(this::skillOperatorsKey).toList();
    }

    /**
     * Returns all skill-group priority queue ZSET keys in deterministic {@link SkillGroup} order.
     */
    public List<String> allQueueKeys() {
        return Arrays.stream(SkillGroup.values()).map(this::queueKey).toList();
    }

    private static OperatorId requireOperatorId(OperatorId operatorId) {
        return Objects.requireNonNull(operatorId, "operatorId must not be null");
    }

    private static SkillGroup requireSkillGroup(SkillGroup skillGroup) {
        return Objects.requireNonNull(skillGroup, "skillGroup must not be null");
    }
}
