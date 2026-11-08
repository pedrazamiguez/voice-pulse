package com.voicepulse.gateway.infrastructure.persistence.memory;

import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.shared.model.CallSessionId;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, non-blocking in-memory adapter implementing {@link SessionRegistryPort}.
 *
 * <p>Backed by {@link ConcurrentHashMap} for local session tracking.
 */
public class InMemorySessionRegistry implements SessionRegistryPort {

    private static final String SESSION_ID_NULL_MSG = "sessionId cannot be null";

    private final ConcurrentHashMap<CallSessionId, CallSession> sessions =
            new ConcurrentHashMap<>();

    @Override
    public void save(CallSession session) {
        Objects.requireNonNull(session, "session cannot be null");
        sessions.put(session.id(), session);
    }

    @Override
    public Optional<CallSession> findById(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void remove(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        sessions.remove(sessionId);
    }

    @Override
    public boolean exists(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_NULL_MSG);
        return sessions.containsKey(sessionId);
    }

    @Override
    public int activeSessionCount() {
        return sessions.size();
    }
}
