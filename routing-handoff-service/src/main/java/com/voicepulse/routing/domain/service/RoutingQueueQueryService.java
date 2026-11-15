package com.voicepulse.routing.domain.service;

import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.inbound.InspectRoutingQueueUseCase;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure Java 21 domain query service implementing {@link InspectRoutingQueueUseCase} over outbound
 * priority queue and ticket persistence ports.
 */
public class RoutingQueueQueryService implements InspectRoutingQueueUseCase {

    private static final Comparator<QueuedTicketItem> GLOBAL_QUEUE_ORDER =
            Comparator.comparingInt(QueuedTicketItem::priorityScore)
                    .reversed()
                    .thenComparing(QueuedTicketItem::enqueuedAt);

    private final PriorityCallQueuePort priorityCallQueuePort;
    private final TicketPersistencePort ticketPersistencePort;

    public RoutingQueueQueryService(
            PriorityCallQueuePort priorityCallQueuePort,
            TicketPersistencePort ticketPersistencePort) {
        this.priorityCallQueuePort =
                Objects.requireNonNull(
                        priorityCallQueuePort, "priorityCallQueuePort cannot be null");
        this.ticketPersistencePort =
                Objects.requireNonNull(
                        ticketPersistencePort, "ticketPersistencePort cannot be null");
    }

    @Override
    public List<QueuedTicketItem> listQueuedBySkillGroup(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup cannot be null");
        return List.copyOf(priorityCallQueuePort.listQueuedBySkillGroup(skillGroup));
    }

    @Override
    public List<QueuedTicketItem> listAllQueued() {
        List<QueuedTicketItem> allItems = new ArrayList<>();
        for (SkillGroup skillGroup : SkillGroup.values()) {
            allItems.addAll(priorityCallQueuePort.listQueuedBySkillGroup(skillGroup));
        }
        allItems.sort(GLOBAL_QUEUE_ORDER);
        return List.copyOf(allItems);
    }

    @Override
    public Map<SkillGroup, Long> queueDepthsBySkillGroup() {
        Map<SkillGroup, Long> depths = new EnumMap<>(SkillGroup.class);
        for (SkillGroup skillGroup : SkillGroup.values()) {
            depths.put(skillGroup, Math.max(0L, priorityCallQueuePort.queueDepth(skillGroup)));
        }
        return Collections.unmodifiableMap(depths);
    }

    @Override
    public long queueDepth(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup cannot be null");
        return Math.max(0L, priorityCallQueuePort.queueDepth(skillGroup));
    }

    @Override
    public Optional<TicketFormFilledEvent> findTicketByCallId(CallId callId) {
        Objects.requireNonNull(callId, "callId cannot be null");
        return ticketPersistencePort.findTicketByCallId(callId);
    }
}
