package com.voicepulse.routing.domain.service;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure domain worker responsible for priority-ordered queue draining across operator skill pools
 * and atomic operator claim coordination with compensating re-enqueue on lost claim races.
 */
public class QueuedCallDrainService {

    private static final Comparator<SkillGroup> DRAIN_SKILL_ORDER =
            Comparator.comparingInt(SkillGroup::defaultPriorityScore)
                    .reversed()
                    .thenComparing(SkillGroup::name);

    private final OperatorPresencePort operatorPresencePort;
    private final PriorityCallQueuePort priorityCallQueuePort;
    private final HandoffPersistenceCoordinator persistenceCoordinator;
    private final OperatorRerouteDispatcher dispatcher;

    public QueuedCallDrainService(
            OperatorPresencePort operatorPresencePort,
            PriorityCallQueuePort priorityCallQueuePort,
            HandoffPersistenceCoordinator persistenceCoordinator,
            OperatorRerouteDispatcher dispatcher) {
        this.operatorPresencePort =
                Objects.requireNonNull(operatorPresencePort, "operatorPresencePort cannot be null");
        this.priorityCallQueuePort =
                Objects.requireNonNull(
                        priorityCallQueuePort, "priorityCallQueuePort cannot be null");
        this.persistenceCoordinator =
                Objects.requireNonNull(
                        persistenceCoordinator, "persistenceCoordinator cannot be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
    }

    /** Returns the operator's presence snapshot only when they can currently accept a call. */
    public Optional<OperatorPresence> findAvailableOperator(OperatorId operatorId) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        return operatorPresencePort
                .findPresence(operatorId)
                .filter(presence -> presence.status().canAcceptCall());
    }

    /**
     * Drains the highest-priority waiting ticket matching the operator's skills in descending
     * {@link SkillGroup#defaultPriorityScore()} order and assigns it atomically.
     */
    public Optional<HandoffAssignment> drainNextForSkills(
            OperatorId operatorId, Set<SkillGroup> skills, Instant claimedAt) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(skills, "skills cannot be null");
        Objects.requireNonNull(claimedAt, "claimedAt cannot be null");

        List<SkillGroup> orderedSkills = skills.stream().sorted(DRAIN_SKILL_ORDER).toList();
        for (SkillGroup skill : orderedSkills) {
            Optional<QueuedTicketItem> dequeued =
                    priorityCallQueuePort.dequeueHighestPriority(skill);
            if (dequeued.isPresent()) {
                return claimQueuedItem(operatorId, dequeued.get(), claimedAt);
            }
        }
        return Optional.empty();
    }

    /**
     * Atomically claims an operator for a dequeued ticket item, re-enqueueing the ticket cleanly if
     * a concurrent assignment claimed the operator first.
     */
    public Optional<HandoffAssignment> claimQueuedItem(
            OperatorId operatorId, QueuedTicketItem queuedItem, Instant claimedAt) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(queuedItem, "queuedItem cannot be null");
        Objects.requireNonNull(claimedAt, "claimedAt cannot be null");

        if (!operatorPresencePort.tryClaimOperator(operatorId, queuedItem.callId(), claimedAt)) {
            priorityCallQueuePort.enqueue(queuedItem);
            dispatcher.broadcastQueueDepth(
                    queuedItem.skillGroup(),
                    priorityCallQueuePort.queueDepth(queuedItem.skillGroup()));
            return Optional.empty();
        }

        HandoffAssignment assignment =
                dispatcher.createAssignment(
                        queuedItem.callId(),
                        queuedItem.ticketId(),
                        operatorId,
                        queuedItem.skillGroup(),
                        claimedAt);
        HandoffLifecycleState.Assigned assignedState =
                new HandoffLifecycleState.Assigned(
                        queuedItem.callId(),
                        queuedItem.ticketId(),
                        operatorId,
                        queuedItem.skillGroup(),
                        claimedAt);

        persistenceCoordinator.recordQueuedClaim(queuedItem, assignment, claimedAt);
        dispatcher.broadcastQueueDepth(
                queuedItem.skillGroup(), priorityCallQueuePort.queueDepth(queuedItem.skillGroup()));
        dispatcher.dispatchAssignment(
                assignment, queuedItem.ticketEvent(), assignedState, claimedAt);
        return Optional.of(assignment);
    }
}
