package com.voicepulse.gateway.domain.ports.outbound;

import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.shared.model.CallSessionId;
import java.util.Optional;

/** Driven (outbound) SPI port for storing and retrieving active call sessions. */
public interface SessionRegistryPort {

    /**
     * Persists or updates the call session in the registry.
     *
     * @param session call session aggregate to save
     */
    void save(CallSession session);

    /**
     * Finds a call session by its identifier.
     *
     * @param sessionId identifier of the session
     * @return Optional containing the session, or empty if not present
     */
    Optional<CallSession> findById(CallSessionId sessionId);

    /**
     * Removes a session from the registry upon termination.
     *
     * @param sessionId identifier of the session to remove
     */
    void remove(CallSessionId sessionId);

    /**
     * Checks if a session with the given identifier is active in the registry.
     *
     * @param sessionId identifier to check
     * @return true if session exists, false otherwise
     */
    boolean exists(CallSessionId sessionId);

    /**
     * Returns the number of currently active sessions.
     *
     * @return active session count
     */
    int activeSessionCount();
}
