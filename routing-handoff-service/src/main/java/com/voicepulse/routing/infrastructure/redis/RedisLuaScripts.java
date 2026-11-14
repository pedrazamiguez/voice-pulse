package com.voicepulse.routing.infrastructure.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Loads and caches atomic Redis 7 Lua scripts from {@code classpath:redis/*.lua} for presence,
 * heartbeat, claim, and priority queue operations.
 */
@Component
public class RedisLuaScripts {

    private final RedisScript<Long> saveOperatorPresenceScript;
    private final RedisScript<Long> claimOperatorScript;
    private final RedisScript<Long> releaseOperatorScript;
    private final RedisScript<Long> refreshHeartbeatScript;
    private final RedisScript<Long> enqueuePriorityCallScript;
    private final RedisScript<String> dequeuePriorityCallScript;
    private final RedisScript<String> removePriorityCallScript;

    public RedisLuaScripts() {
        this.saveOperatorPresenceScript =
                RedisScript.of(
                        new ClassPathResource("redis/save_operator_presence.lua"), Long.class);
        this.claimOperatorScript =
                RedisScript.of(new ClassPathResource("redis/claim_operator.lua"), Long.class);
        this.releaseOperatorScript =
                RedisScript.of(new ClassPathResource("redis/release_operator.lua"), Long.class);
        this.refreshHeartbeatScript =
                RedisScript.of(new ClassPathResource("redis/refresh_heartbeat.lua"), Long.class);
        this.enqueuePriorityCallScript =
                RedisScript.of(
                        new ClassPathResource("redis/enqueue_priority_call.lua"), Long.class);
        this.dequeuePriorityCallScript =
                RedisScript.of(
                        new ClassPathResource("redis/dequeue_priority_call.lua"), String.class);
        this.removePriorityCallScript =
                RedisScript.of(
                        new ClassPathResource("redis/remove_priority_call.lua"), String.class);
    }

    public RedisScript<Long> saveOperatorPresenceScript() {
        return saveOperatorPresenceScript;
    }

    public RedisScript<Long> claimOperatorScript() {
        return claimOperatorScript;
    }

    public RedisScript<Long> releaseOperatorScript() {
        return releaseOperatorScript;
    }

    public RedisScript<Long> refreshHeartbeatScript() {
        return refreshHeartbeatScript;
    }

    public RedisScript<Long> enqueuePriorityCallScript() {
        return enqueuePriorityCallScript;
    }

    public RedisScript<String> dequeuePriorityCallScript() {
        return dequeuePriorityCallScript;
    }

    public RedisScript<String> removePriorityCallScript() {
        return removePriorityCallScript;
    }
}
