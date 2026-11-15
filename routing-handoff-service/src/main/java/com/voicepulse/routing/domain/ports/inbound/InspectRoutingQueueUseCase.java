package com.voicepulse.routing.domain.ports.inbound;

import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Driving (inbound) query port for inspecting prioritized skill-group call queues, real-time queue
 * depths, and persisted disruption ticket payloads for the Operator Web Console.
 */
public interface InspectRoutingQueueUseCase {

    /**
     * Lists queued ticket items for a specific {@link SkillGroup} in priority-FIFO order.
     *
     * @param skillGroup target skill group queue
     * @return immutable ordered list of queued ticket items
     */
    List<QueuedTicketItem> listQueuedBySkillGroup(SkillGroup skillGroup);

    /**
     * Lists all queued ticket items across all skill-group queues ordered by descending priority
     * score and ascending enqueue timestamp.
     *
     * @return immutable ordered list of all queued ticket items
     */
    List<QueuedTicketItem> listAllQueued();

    /**
     * Returns current queue depths keyed by every {@link SkillGroup}.
     *
     * @return immutable map from skill group to queue depth
     */
    Map<SkillGroup, Long> queueDepthsBySkillGroup();

    /**
     * Returns the current queue depth for the specified {@link SkillGroup}.
     *
     * @param skillGroup target skill group queue
     * @return queue depth count
     */
    long queueDepth(SkillGroup skillGroup);

    /**
     * Looks up a disruption ticket payload by its {@link CallId}.
     *
     * @param callId call identifier
     * @return ticket event payload if found
     */
    Optional<TicketFormFilledEvent> findTicketByCallId(CallId callId);
}
