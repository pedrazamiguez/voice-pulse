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

    private final ConcurrentHashMap<CallSessionId, CallSession> sessions =
            new ConcurrentHashMap<>();

    @Override
    public void save(CallSession session) {
        Objects.requireNonNull(session, "session cannot be null");
        sessions.put(session.id(), session);
    }

    @Override
    public Optional<CallSession> findById(CallSessionId sessionId) {
        validateSessionId(sessionId);
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void remove(CallSessionId sessionId) {
        validateSessionId(sessionId);
        sessions.remove(sessionId);
    }

    @Override
    public boolean exists(CallSessionId sessionId) {
        validateSessionId(sessionId);
        return sessions.containsKey(sessionId);
    }

    @Override
    public int activeSessionCount() {
        return sessions.size();
    }

    private void validateSessionId(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
    }
}
