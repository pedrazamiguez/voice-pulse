package com.voicepulse.conversation.infrastructure.fallback;

import com.voicepulse.conversation.domain.model.OperatorHandoffReceipt;
import com.voicepulse.conversation.domain.model.OperatorQueueStatus;
import com.voicepulse.conversation.domain.ports.outbound.OperatorRoutingPort;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op implementation of {@link OperatorRoutingPort} (Null Object Pattern).
 *
 * <p>Registered as a Spring {@link Fallback} bean so that it is automatically superseded once the
 * concrete {@code mcp-telephony-routing} client adapter is registered.
 */
@Slf4j
@Fallback
@Component
public class NoOpOperatorRoutingPort implements OperatorRoutingPort {

    private static final String FALLBACK_STATUS = "NO_OP_FALLBACK";

    @Override
    public OperatorQueueStatus checkQueueStatus(String department) {
        log.debug("No-op OperatorRoutingPort checking queue status: department={}", department);
        return new OperatorQueueStatus(department, 0, 0, 0);
    }

    @Override
    public OperatorHandoffReceipt transferToHumanOperator(
            CallId callId, String department, UUID ticketId) {
        log.debug(
                "No-op OperatorRoutingPort transferring call: callId={}, department={},"
                        + " ticketId={}",
                callId,
                department,
                ticketId);
        return new OperatorHandoffReceipt(
                callId, ticketId, department, FALLBACK_STATUS, 0, 0, Instant.now());
    }
}
