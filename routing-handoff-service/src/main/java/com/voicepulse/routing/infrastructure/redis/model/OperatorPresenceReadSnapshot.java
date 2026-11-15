package com.voicepulse.routing.infrastructure.redis.model;

import com.voicepulse.routing.domain.model.OperatorId;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot produced by a pipelined reactive read of an operator's heartbeat liveness key
 * and Redis presence hash entries.
 */
public record OperatorPresenceReadSnapshot(
        OperatorId operatorId, boolean heartbeatActive, Map<String, String> hashEntries) {

    public OperatorPresenceReadSnapshot {
        Objects.requireNonNull(operatorId, "operatorId must not be null");
        hashEntries =
                hashEntries == null || hashEntries.isEmpty() ? Map.of() : Map.copyOf(hashEntries);
    }

    @Override
    public Map<String, String> hashEntries() {
        return Collections.unmodifiableMap(hashEntries);
    }
}
