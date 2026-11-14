package com.voicepulse.routing.infrastructure.fallback;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.ports.inbound.CompleteHandoffUseCase;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/** Fallback no-op implementation of {@link CompleteHandoffUseCase} (Null Object Pattern). */
@Slf4j
@Fallback
@Component
public class NoOpCompleteHandoffUseCase implements CompleteHandoffUseCase {

    @Override
    public HandoffLifecycleState completeHandoff(
            CallId callId, OperatorId operatorId, String resolutionNotes, Instant completedAt) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(completedAt, "completedAt cannot be null");
        log.debug("No-op completeHandoff: callId={}, operatorId={}", callId, operatorId);
        String notes = resolutionNotes == null ? "" : resolutionNotes;
        return new HandoffLifecycleState.Completed(
                callId, callId.value(), operatorId, notes, completedAt);
    }

    @Override
    public HandoffLifecycleState markCallAbandoned(
            CallId callId, String reason, Instant abandonedAt) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(abandonedAt, "abandonedAt cannot be null");
        log.debug("No-op markCallAbandoned: callId={}, reason={}", callId, reason);
        String resolvedReason = (reason == null || reason.isBlank()) ? "NO_OP_FALLBACK" : reason;
        return new HandoffLifecycleState.Abandoned(
                callId, callId.value(), resolvedReason, abandonedAt);
    }
}
