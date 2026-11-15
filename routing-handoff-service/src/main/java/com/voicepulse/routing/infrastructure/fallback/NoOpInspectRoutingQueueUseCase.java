package com.voicepulse.routing.infrastructure.fallback;

import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.inbound.InspectRoutingQueueUseCase;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/** Fallback no-op implementation of {@link InspectRoutingQueueUseCase} (Null Object Pattern). */
@Slf4j
@Fallback
@Component
public class NoOpInspectRoutingQueueUseCase implements InspectRoutingQueueUseCase {

    @Override
    public List<QueuedTicketItem> listQueuedBySkillGroup(SkillGroup skillGroup) {
        log.debug("No-op listQueuedBySkillGroup: skillGroup={}", skillGroup);
        return List.of();
    }

    @Override
    public List<QueuedTicketItem> listAllQueued() {
        log.debug("No-op listAllQueued");
        return List.of();
    }

    @Override
    public Map<SkillGroup, Long> queueDepthsBySkillGroup() {
        log.debug("No-op queueDepthsBySkillGroup");
        return Map.of();
    }

    @Override
    public long queueDepth(SkillGroup skillGroup) {
        log.debug("No-op queueDepth: skillGroup={}", skillGroup);
        return 0L;
    }

    @Override
    public Optional<TicketFormFilledEvent> findTicketByCallId(CallId callId) {
        log.debug("No-op findTicketByCallId: callId={}", callId);
        return Optional.empty();
    }
}
