package com.voicepulse.conversation.domain.ports.outbound;

import com.voicepulse.conversation.domain.model.OperatorHandoffReceipt;
import com.voicepulse.conversation.domain.model.OperatorQueueStatus;
import com.voicepulse.shared.model.CallId;
import java.util.UUID;

/**
 * Driven (outbound) port for checking human operator queue availability and dispatching call
 * handoffs via {@code mcp-telephony-routing} (RFC-0001 §4.4 & RFC-0003 §4.3).
 */
public interface OperatorRoutingPort {

    /**
     * Queries current operator availability and estimated wait time for the given skill department.
     *
     * @param department target operator skill group (e.g. {@code DISRUPTION_DESK_GOLD})
     * @return real-time queue status
     */
    OperatorQueueStatus checkQueueStatus(String department);

    /**
     * Transfers an active call session and its finalized ticket to a human operator skill queue.
     *
     * @param callId active call identifier
     * @param department target operator skill group
     * @param ticketId finalized disruption ticket identifier
     * @return operator handoff receipt with queue position and estimated wait time
     */
    OperatorHandoffReceipt transferToHumanOperator(CallId callId, String department, UUID ticketId);
}
