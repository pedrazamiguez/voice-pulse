package com.voicepulse.routing.infrastructure.fallback;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.OperatorDispatchPort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/** Fallback no-op implementation of {@link OperatorDispatchPort} (Null Object Pattern). */
@Slf4j
@Fallback
@Component
public class NoOpOperatorDispatchPort implements OperatorDispatchPort {

    @Override
    public void pushAssignment(HandoffAssignment assignment, TicketFormFilledEvent ticketEvent) {
        log.debug(
                "No-op pushAssignment: callId={}, operatorId={}",
                assignment.callId(),
                assignment.operatorId());
    }

    @Override
    public void broadcastQueueMetrics(SkillGroup skillGroup, long queueDepth) {
        log.debug(
                "No-op broadcastQueueMetrics: skillGroup={}, queueDepth={}",
                skillGroup,
                queueDepth);
    }

    @Override
    public void notifyHandoffStateChanged(HandoffLifecycleState state) {
        log.debug("No-op notifyHandoffStateChanged: state={}", state);
    }
}
