package com.voicepulse.gateway.domain.ports.inbound;

import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.gateway.domain.model.DisconnectReason;
import com.voicepulse.shared.model.CallSessionId;
import java.util.Optional;

/**
 * Driving (inbound) port for managing call session lifecycle (connection, termination, queries).
 */
public interface ManageCallSessionUseCase {

    /**
     * Starts and registers a new call session.
     *
     * @param sessionId identifier of the call session
     * @return the initialized CallSession aggregate
     */
    CallSession startSession(CallSessionId sessionId);

    /**
     * Terminates an active call session with the specified reason.
     *
     * @param sessionId identifier of the call session
     * @param reason cause of disconnection
     */
    void terminateSession(CallSessionId sessionId, DisconnectReason reason);

    /**
     * Retrieves an active call session by its identifier.
     *
     * @param sessionId identifier of the call session
     * @return an Optional containing the CallSession, or empty if not found
     */
    Optional<CallSession> getSession(CallSessionId sessionId);
}
