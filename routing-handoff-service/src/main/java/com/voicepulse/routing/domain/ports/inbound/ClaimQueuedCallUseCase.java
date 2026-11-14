package com.voicepulse.routing.domain.ports.inbound;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Optional;

/**
 * Driving (inbound) port for claiming queued disruption calls either automatically (next highest
 * priority) or manually by call identifier from the Operator Web Console.
 */
public interface ClaimQueuedCallUseCase {

    /**
     * Claims the highest-priority queued call matching the operator's skills.
     *
     * @param operatorId operator claiming the call
     * @param claimedAt claim timestamp
     * @return confirmed handoff assignment if a matching queued call exists
     */
    Optional<HandoffAssignment> claimNextQueuedCall(OperatorId operatorId, Instant claimedAt);

    /**
     * Claims a specific queued call by its {@link CallId}.
     *
     * @param operatorId operator claiming the call
     * @param callId queued call identifier
     * @param claimedAt claim timestamp
     * @return confirmed handoff assignment if the call was queued and operator claimed it
     */
    Optional<HandoffAssignment> claimSpecificCall(
            OperatorId operatorId, CallId callId, Instant claimedAt);
}
