package com.voicepulse.routing.domain.ports.outbound;

import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.shared.model.CallId;
import java.util.List;
import java.util.Optional;

/** Driven (outbound) SPI port for priority-FIFO skill-group call queues backed by Redis 7. */
public interface PriorityCallQueuePort {

    /**
     * Enqueues a ticket item into its skill-group priority queue.
     *
     * @param item queued ticket item
     * @return 1-based queue position after insertion
     */
    int enqueue(QueuedTicketItem item);

    /**
     * Atomically pops the highest-priority (and earliest-enqueued on ties) item from the queue.
     *
     * @param skillGroup target skill group queue
     * @return dequeued item if non-empty
     */
    Optional<QueuedTicketItem> dequeueHighestPriority(SkillGroup skillGroup);

    /**
     * Removes a specific call from any skill-group queue (for manual claim or abandonment).
     *
     * @param callId call identifier to remove
     * @return removed queued item if found
     */
    Optional<QueuedTicketItem> removeByCallId(CallId callId);

    /**
     * Lists queued items for a skill group in priority-FIFO dequeue order.
     *
     * @param skillGroup target skill group queue
     * @return ordered list of queued items
     */
    List<QueuedTicketItem> listQueuedBySkillGroup(SkillGroup skillGroup);

    /**
     * Returns the current number of queued calls for the given skill group.
     *
     * @param skillGroup target skill group queue
     * @return queue depth
     */
    long queueDepth(SkillGroup skillGroup);
}
