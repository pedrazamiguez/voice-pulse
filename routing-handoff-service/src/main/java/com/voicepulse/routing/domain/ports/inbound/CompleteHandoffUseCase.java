package com.voicepulse.routing.domain.ports.inbound;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;

/**
 * Driving (inbound) port for completing an active operator handoff or recording caller abandonment
 * before assignment.
 */
public interface CompleteHandoffUseCase {

    /**
     * Marks an operator-handled call as completed and records resolution notes.
     *
     * @param callId active call identifier
     * @param operatorId operator completing the handoff
     * @param resolutionNotes operator resolution summary
     * @param completedAt completion timestamp
     * @return terminal {@link HandoffLifecycleState.Completed} state
     */
    HandoffLifecycleState completeHandoff(
            CallId callId, OperatorId operatorId, String resolutionNotes, Instant completedAt);

    /**
     * Marks a queued or assigned call as abandoned prior to completion.
     *
     * @param callId call identifier
     * @param reason abandonment reason
     * @param abandonedAt abandonment timestamp
     * @return terminal {@link HandoffLifecycleState.Abandoned} state
     */
    HandoffLifecycleState markCallAbandoned(CallId callId, String reason, Instant abandonedAt);
}
