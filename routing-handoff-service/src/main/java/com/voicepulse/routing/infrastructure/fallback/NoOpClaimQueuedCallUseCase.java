package com.voicepulse.routing.infrastructure.fallback;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.ports.inbound.ClaimQueuedCallUseCase;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/** Fallback no-op implementation of {@link ClaimQueuedCallUseCase} (Null Object Pattern). */
@Slf4j
@Fallback
@Component
public class NoOpClaimQueuedCallUseCase implements ClaimQueuedCallUseCase {

    @Override
    public Optional<HandoffAssignment> claimNextQueuedCall(
            OperatorId operatorId, Instant claimedAt) {
        log.debug("No-op claimNextQueuedCall: operatorId={}, claimedAt={}", operatorId, claimedAt);
        return Optional.empty();
    }

    @Override
    public Optional<HandoffAssignment> claimSpecificCall(
            OperatorId operatorId, CallId callId, Instant claimedAt) {
        log.debug(
                "No-op claimSpecificCall: operatorId={}, callId={}, claimedAt={}",
                operatorId,
                callId,
                claimedAt);
        return Optional.empty();
    }
}
