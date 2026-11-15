package com.voicepulse.gateway.domain.model;

import com.voicepulse.shared.model.CallSessionId;
import java.util.Objects;

/**
 * Sealed authorization outcome when an operator WebSocket attempts to attach to an active call
 * audio bridge.
 */
public sealed interface BridgeAuthorizationStatus
        permits BridgeAuthorizationStatus.Authorized,
                BridgeAuthorizationStatus.PendingReroute,
                BridgeAuthorizationStatus.UnauthorizedOperator,
                BridgeAuthorizationStatus.SessionUnavailable {

    /** Operator matches the active bridged assignment for the call session. */
    record Authorized(CallSessionId sessionId, String operatorId)
            implements BridgeAuthorizationStatus {

        public Authorized {
            Objects.requireNonNull(sessionId, "sessionId cannot be null");
            if (operatorId == null || operatorId.isBlank()) {
                throw new IllegalArgumentException("operatorId cannot be null or blank");
            }
        }
    }

    /** Call session is active but has not yet received an operator reroute transition. */
    record PendingReroute(CallSessionId sessionId) implements BridgeAuthorizationStatus {

        public PendingReroute {
            Objects.requireNonNull(sessionId, "sessionId cannot be null");
        }
    }

    /** Call session is bridged to a different operator identifier. */
    record UnauthorizedOperator(
            CallSessionId sessionId, String requestedOperatorId, String assignedOperatorId)
            implements BridgeAuthorizationStatus {

        public UnauthorizedOperator {
            Objects.requireNonNull(sessionId, "sessionId cannot be null");
            if (requestedOperatorId == null || requestedOperatorId.isBlank()) {
                throw new IllegalArgumentException("requestedOperatorId cannot be null or blank");
            }
            if (assignedOperatorId == null || assignedOperatorId.isBlank()) {
                throw new IllegalArgumentException("assignedOperatorId cannot be null or blank");
            }
        }
    }

    /** Call session does not exist or has already disconnected. */
    record SessionUnavailable(CallSessionId sessionId) implements BridgeAuthorizationStatus {

        public SessionUnavailable {
            Objects.requireNonNull(sessionId, "sessionId cannot be null");
        }
    }
}
