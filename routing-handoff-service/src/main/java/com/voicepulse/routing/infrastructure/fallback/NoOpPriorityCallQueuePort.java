package com.voicepulse.routing.infrastructure.fallback;

import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.shared.model.CallId;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/** Fallback no-op implementation of {@link PriorityCallQueuePort} (Null Object Pattern). */
@Slf4j
@Fallback
@Component
public class NoOpPriorityCallQueuePort implements PriorityCallQueuePort {

    @Override
    public int enqueue(QueuedTicketItem item) {
        log.debug("No-op enqueue: item={}", item);
        return 0;
    }

    @Override
    public Optional<QueuedTicketItem> dequeueHighestPriority(SkillGroup skillGroup) {
        log.debug("No-op dequeueHighestPriority: skillGroup={}", skillGroup);
        return Optional.empty();
    }

    @Override
    public Optional<QueuedTicketItem> removeByCallId(CallId callId) {
        log.debug("No-op removeByCallId: callId={}", callId);
        return Optional.empty();
    }

    @Override
    public List<QueuedTicketItem> listQueuedBySkillGroup(SkillGroup skillGroup) {
        log.debug("No-op listQueuedBySkillGroup: skillGroup={}", skillGroup);
        return List.of();
    }

    @Override
    public long queueDepth(SkillGroup skillGroup) {
        log.debug("No-op queueDepth: skillGroup={}", skillGroup);
        return 0L;
    }
}
