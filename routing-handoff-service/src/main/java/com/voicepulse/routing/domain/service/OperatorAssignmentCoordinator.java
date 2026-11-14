package com.voicepulse.routing.domain.service;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.model.SkillMatchPlan;
import com.voicepulse.routing.domain.ports.inbound.ClaimQueuedCallUseCase;
import com.voicepulse.routing.domain.ports.inbound.CompleteHandoffUseCase;
import com.voicepulse.routing.domain.ports.inbound.ManageOperatorPresenceUseCase;
import com.voicepulse.routing.domain.ports.inbound.ProcessTicketIntentUseCase;
import com.voicepulse.routing.domain.ports.outbound.AudioRerouteSignalPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorDispatchPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
import com.voicepulse.shared.event.OperatorRerouteSignal;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure Java 21 domain coordinator orchestrating incoming disruption ticket routing, atomic operator
 * matching, priority fallback queueing, automatic queue draining, and audio reroute signaling.
 */
public class OperatorAssignmentCoordinator
        implements ProcessTicketIntentUseCase,
                ManageOperatorPresenceUseCase,
                ClaimQueuedCallUseCase,
                CompleteHandoffUseCase {

    private static final String STATE_QUEUED = "QUEUED";
    private static final String STATE_ASSIGNED = "ASSIGNED";
    private static final String STATE_COMPLETED = "COMPLETED";
    private static final String STATE_ABANDONED = "ABANDONED";

    private static final String EVENT_TICKET_QUEUED = "TICKET_QUEUED";
    private static final String EVENT_OPERATOR_ASSIGNED = "OPERATOR_ASSIGNED";
    private static final String EVENT_HANDOFF_COMPLETED = "HANDOFF_COMPLETED";
    private static final String EVENT_CALL_ABANDONED = "CALL_ABANDONED";
    private static final String DEFAULT_ABANDON_REASON = "CALLER_DISCONNECTED";

    private static final Comparator<SkillGroup> DRAIN_SKILL_ORDER =
            Comparator.comparingInt(SkillGroup::defaultPriorityScore)
                    .reversed()
                    .thenComparing(SkillGroup::name);

    private final SkillGroupMatchingPolicy matchingPolicy;
    private final TicketPersistencePort ticketPersistencePort;
    private final OperatorPresencePort operatorPresencePort;
    private final PriorityCallQueuePort priorityCallQueuePort;
    private final OperatorDispatchPort operatorDispatchPort;
    private final AudioRerouteSignalPort audioRerouteSignalPort;
    private final String operatorBridgeBasePath;
    private final Clock clock;
    private final AtomicLong rerouteSequence = new AtomicLong(1L);

    public OperatorAssignmentCoordinator(
            SkillGroupMatchingPolicy matchingPolicy,
            TicketPersistencePort ticketPersistencePort,
            OperatorPresencePort operatorPresencePort,
            PriorityCallQueuePort priorityCallQueuePort,
            OperatorDispatchPort operatorDispatchPort,
            AudioRerouteSignalPort audioRerouteSignalPort,
            String operatorBridgeBasePath,
            Clock clock) {
        this.matchingPolicy =
                Objects.requireNonNull(matchingPolicy, "matchingPolicy cannot be null");
        this.ticketPersistencePort =
                Objects.requireNonNull(
                        ticketPersistencePort, "ticketPersistencePort cannot be null");
        this.operatorPresencePort =
                Objects.requireNonNull(operatorPresencePort, "operatorPresencePort cannot be null");
        this.priorityCallQueuePort =
                Objects.requireNonNull(
                        priorityCallQueuePort, "priorityCallQueuePort cannot be null");
        this.operatorDispatchPort =
                Objects.requireNonNull(operatorDispatchPort, "operatorDispatchPort cannot be null");
        this.audioRerouteSignalPort =
                Objects.requireNonNull(
                        audioRerouteSignalPort, "audioRerouteSignalPort cannot be null");
        this.operatorBridgeBasePath = normalizeBridgeBasePath(operatorBridgeBasePath);
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    @Override
    public HandoffLifecycleState processTicketIntent(TicketFormFilledEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        SkillMatchPlan plan = matchingPolicy.evaluate(event);
        Instant now = clock.instant();

        Optional<HandoffLifecycleState> immediateAssignment =
                tryAssignAvailableOperator(event, plan, now);
        return immediateAssignment.orElseGet(() -> enqueueUnmatchedTicket(event, plan, now));
    }

    @Override
    public OperatorPresence updatePresence(
            OperatorId operatorId,
            String displayName,
            OperatorAvailabilityStatus status,
            Set<SkillGroup> skills,
            Instant timestamp) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(displayName, "displayName cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(skills, "skills cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        Optional<OperatorPresence> existing = operatorPresencePort.findPresence(operatorId);
        Optional<CallId> retainedCall =
                status == OperatorAvailabilityStatus.AVAILABLE
                        ? Optional.empty()
                        : existing.flatMap(OperatorPresence::activeCallId);

        OperatorPresence updated =
                new OperatorPresence(
                        operatorId, displayName, status, skills, retainedCall, timestamp);
        operatorPresencePort.savePresence(updated);

        if (status == OperatorAvailabilityStatus.AVAILABLE) {
            Optional<HandoffAssignment> drained =
                    drainNextQueuedCallForSkills(operatorId, updated.skills(), timestamp);
            if (drained.isPresent()) {
                return updated.assignCall(drained.get().callId(), timestamp);
            }
        }
        return updated;
    }

    @Override
    public Optional<OperatorPresence> refreshHeartbeat(OperatorId operatorId, Instant timestamp) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        if (!operatorPresencePort.refreshHeartbeat(operatorId, timestamp)) {
            return Optional.empty();
        }
        return operatorPresencePort
                .findPresence(operatorId)
                .map(presence -> presence.refreshHeartbeat(timestamp));
    }

    @Override
    public Optional<OperatorPresence> getPresence(OperatorId operatorId) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        return operatorPresencePort.findPresence(operatorId);
    }

    @Override
    public List<OperatorPresence> listOperatorsBySkillGroup(SkillGroup skillGroup) {
        Objects.requireNonNull(skillGroup, "skillGroup cannot be null");
        return operatorPresencePort.findBySkillGroup(skillGroup);
    }

    @Override
    public Optional<HandoffAssignment> claimNextQueuedCall(
            OperatorId operatorId, Instant claimedAt) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(claimedAt, "claimedAt cannot be null");

        Optional<OperatorPresence> operator = findAvailableOperator(operatorId);
        if (operator.isEmpty()) {
            return Optional.empty();
        }
        return drainNextQueuedCallForSkills(operatorId, operator.get().skills(), claimedAt);
    }

    @Override
    public Optional<HandoffAssignment> claimSpecificCall(
            OperatorId operatorId, CallId callId, Instant claimedAt) {
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(claimedAt, "claimedAt cannot be null");

        Optional<OperatorPresence> operator = findAvailableOperator(operatorId);
        if (operator.isEmpty()) {
            return Optional.empty();
        }

        Optional<QueuedTicketItem> removedItem = priorityCallQueuePort.removeByCallId(callId);
        if (removedItem.isEmpty()) {
            return Optional.empty();
        }
        return claimQueuedItemForOperator(operatorId, removedItem.get(), claimedAt);
    }

    @Override
    public HandoffLifecycleState completeHandoff(
            CallId callId, OperatorId operatorId, String resolutionNotes, Instant completedAt) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(operatorId, "operatorId cannot be null");
        Objects.requireNonNull(completedAt, "completedAt cannot be null");

        String notes = resolutionNotes == null ? "" : resolutionNotes.trim();
        Optional<TicketFormFilledEvent> persistedTicket =
                ticketPersistencePort.findTicketByCallId(callId);
        UUID ticketId = persistedTicket.map(TicketFormFilledEvent::ticketId).orElse(callId.value());
        Optional<OperatorPresence> operatorPresence = operatorPresencePort.findPresence(operatorId);

        if (persistedTicket.isPresent()) {
            ticketPersistencePort.updateTicketStatus(ticketId, STATE_COMPLETED, completedAt);
        }
        ticketPersistencePort.recordHandoffAuditLog(
                callId,
                EVENT_HANDOFF_COMPLETED,
                STATE_ASSIGNED,
                STATE_COMPLETED,
                operatorId,
                buildCompletedAuditDetails(ticketId, notes),
                completedAt);

        operatorPresencePort.releaseOperator(
                operatorId, OperatorAvailabilityStatus.AVAILABLE, completedAt);

        HandoffLifecycleState.Completed completedState =
                new HandoffLifecycleState.Completed(
                        callId, ticketId, operatorId, notes, completedAt);
        operatorDispatchPort.notifyHandoffStateChanged(completedState);

        operatorPresence.ifPresent(
                presence ->
                        drainNextQueuedCallForSkills(operatorId, presence.skills(), completedAt));
        return completedState;
    }

    @Override
    public HandoffLifecycleState markCallAbandoned(
            CallId callId, String reason, Instant abandonedAt) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(abandonedAt, "abandonedAt cannot be null");

        String resolvedReason =
                (reason == null || reason.isBlank()) ? DEFAULT_ABANDON_REASON : reason.trim();
        Optional<QueuedTicketItem> removedItem = priorityCallQueuePort.removeByCallId(callId);
        removedItem.ifPresent(item -> broadcastSkillQueueDepth(item.skillGroup()));

        Optional<TicketFormFilledEvent> persistedTicket =
                ticketPersistencePort.findTicketByCallId(callId);
        UUID ticketId =
                removedItem
                        .map(QueuedTicketItem::ticketId)
                        .or(() -> persistedTicket.map(TicketFormFilledEvent::ticketId))
                        .orElse(callId.value());

        String sourceState = resolveAbandonSourceState(removedItem, persistedTicket);
        if (removedItem.isPresent() || persistedTicket.isPresent()) {
            ticketPersistencePort.updateTicketStatus(ticketId, STATE_ABANDONED, abandonedAt);
        }
        ticketPersistencePort.recordHandoffAuditLog(
                callId,
                EVENT_CALL_ABANDONED,
                sourceState,
                STATE_ABANDONED,
                null,
                buildAbandonedAuditDetails(ticketId, resolvedReason),
                abandonedAt);

        HandoffLifecycleState.Abandoned abandonedState =
                new HandoffLifecycleState.Abandoned(callId, ticketId, resolvedReason, abandonedAt);
        operatorDispatchPort.notifyHandoffStateChanged(abandonedState);
        return abandonedState;
    }

    private Optional<HandoffLifecycleState> tryAssignAvailableOperator(
            TicketFormFilledEvent event, SkillMatchPlan plan, Instant now) {
        for (SkillGroup candidateSkill : plan.candidateOrder()) {
            List<OperatorPresence> candidates =
                    operatorPresencePort.findAvailableBySkillGroup(candidateSkill);
            for (OperatorPresence candidate : candidates) {
                if (candidate == null || !candidate.status().canAcceptCall()) {
                    continue;
                }
                if (!operatorPresencePort.tryClaimOperator(
                        candidate.operatorId(), event.callId(), now)) {
                    continue;
                }

                HandoffAssignment assignment =
                        createAssignment(
                                event.callId(),
                                event.ticketId(),
                                candidate.operatorId(),
                                candidateSkill,
                                now);
                HandoffLifecycleState.Assigned assignedState =
                        new HandoffLifecycleState.Assigned(
                                event.callId(),
                                event.ticketId(),
                                candidate.operatorId(),
                                candidateSkill,
                                now);
                persistImmediateAssignmentWithCompensation(
                        event, plan, assignment, assignedState, now);
                dispatchAssignmentNotifications(assignment, event, assignedState, now);
                return Optional.of(assignedState);
            }
        }
        return Optional.empty();
    }

    private void persistImmediateAssignmentWithCompensation(
            TicketFormFilledEvent event,
            SkillMatchPlan plan,
            HandoffAssignment assignment,
            HandoffLifecycleState.Assigned assignedState,
            Instant now) {
        try {
            ticketPersistencePort.saveTicket(event, plan.primarySkillGroup(), assignedState);
            ticketPersistencePort.recordHandoffAuditLog(
                    event.callId(),
                    EVENT_OPERATOR_ASSIGNED,
                    null,
                    STATE_ASSIGNED,
                    assignment.operatorId(),
                    buildAssignmentAuditDetails(assignment, plan.priorityScore()),
                    now);
        } catch (RuntimeException ex) {
            operatorPresencePort.releaseOperator(
                    assignment.operatorId(), OperatorAvailabilityStatus.AVAILABLE, clock.instant());
            throw ex;
        }
    }

    private HandoffLifecycleState enqueueUnmatchedTicket(
            TicketFormFilledEvent event, SkillMatchPlan plan, Instant now) {
        QueuedTicketItem queuedItem =
                new QueuedTicketItem(event, plan.primarySkillGroup(), plan.priorityScore(), now);
        int queuePosition = Math.max(0, priorityCallQueuePort.enqueue(queuedItem));
        HandoffLifecycleState.Queued queuedState =
                new HandoffLifecycleState.Queued(
                        event.callId(),
                        event.ticketId(),
                        plan.primarySkillGroup(),
                        queuePosition,
                        now);

        persistQueuedWithCompensation(event, plan, queuedState, queuePosition, now);
        broadcastSkillQueueDepth(plan.primarySkillGroup());
        operatorDispatchPort.notifyHandoffStateChanged(queuedState);
        return queuedState;
    }

    private void persistQueuedWithCompensation(
            TicketFormFilledEvent event,
            SkillMatchPlan plan,
            HandoffLifecycleState.Queued queuedState,
            int queuePosition,
            Instant now) {
        try {
            ticketPersistencePort.saveTicket(event, plan.primarySkillGroup(), queuedState);
            ticketPersistencePort.recordHandoffAuditLog(
                    event.callId(),
                    EVENT_TICKET_QUEUED,
                    null,
                    STATE_QUEUED,
                    null,
                    buildQueuedAuditDetails(plan, queuePosition),
                    now);
        } catch (RuntimeException ex) {
            priorityCallQueuePort.removeByCallId(event.callId());
            throw ex;
        }
    }

    private Optional<HandoffAssignment> drainNextQueuedCallForSkills(
            OperatorId operatorId, Set<SkillGroup> skills, Instant claimedAt) {
        List<SkillGroup> orderedSkills = skills.stream().sorted(DRAIN_SKILL_ORDER).toList();
        for (SkillGroup skill : orderedSkills) {
            Optional<QueuedTicketItem> dequeued =
                    priorityCallQueuePort.dequeueHighestPriority(skill);
            if (dequeued.isPresent()) {
                return claimQueuedItemForOperator(operatorId, dequeued.get(), claimedAt);
            }
        }
        return Optional.empty();
    }

    private Optional<HandoffAssignment> claimQueuedItemForOperator(
            OperatorId operatorId, QueuedTicketItem queuedItem, Instant claimedAt) {
        if (!operatorPresencePort.tryClaimOperator(operatorId, queuedItem.callId(), claimedAt)) {
            priorityCallQueuePort.enqueue(queuedItem);
            broadcastSkillQueueDepth(queuedItem.skillGroup());
            return Optional.empty();
        }

        HandoffAssignment assignment =
                createAssignment(
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

        persistQueueClaimWithCompensation(queuedItem, assignment, claimedAt);
        broadcastSkillQueueDepth(queuedItem.skillGroup());
        dispatchAssignmentNotifications(
                assignment, queuedItem.ticketEvent(), assignedState, claimedAt);
        return Optional.of(assignment);
    }

    private void persistQueueClaimWithCompensation(
            QueuedTicketItem queuedItem, HandoffAssignment assignment, Instant claimedAt) {
        try {
            ticketPersistencePort.updateTicketStatus(
                    queuedItem.ticketId(), STATE_ASSIGNED, claimedAt);
            ticketPersistencePort.recordHandoffAuditLog(
                    queuedItem.callId(),
                    EVENT_OPERATOR_ASSIGNED,
                    STATE_QUEUED,
                    STATE_ASSIGNED,
                    assignment.operatorId(),
                    buildAssignmentAuditDetails(assignment, queuedItem.priorityScore()),
                    claimedAt);
        } catch (RuntimeException ex) {
            operatorPresencePort.releaseOperator(
                    assignment.operatorId(), OperatorAvailabilityStatus.AVAILABLE, clock.instant());
            priorityCallQueuePort.enqueue(queuedItem);
            throw ex;
        }
    }

    private Optional<OperatorPresence> findAvailableOperator(OperatorId operatorId) {
        return operatorPresencePort
                .findPresence(operatorId)
                .filter(presence -> presence.status().canAcceptCall());
    }

    private void dispatchAssignmentNotifications(
            HandoffAssignment assignment,
            TicketFormFilledEvent ticketEvent,
            HandoffLifecycleState.Assigned assignedState,
            Instant timestamp) {
        operatorDispatchPort.pushAssignment(assignment, ticketEvent);
        operatorDispatchPort.notifyHandoffStateChanged(assignedState);
        OperatorRerouteSignal rerouteSignal =
                new OperatorRerouteSignal(
                        assignment.callId(),
                        rerouteSequence.getAndIncrement(),
                        timestamp,
                        assignment.operatorId().value(),
                        assignment.bridgeEndpoint());
        audioRerouteSignalPort.sendRerouteSignal(rerouteSignal);
    }

    private void broadcastSkillQueueDepth(SkillGroup skillGroup) {
        long depth = priorityCallQueuePort.queueDepth(skillGroup);
        operatorDispatchPort.broadcastQueueMetrics(skillGroup, depth);
    }

    private HandoffAssignment createAssignment(
            CallId callId,
            UUID ticketId,
            OperatorId operatorId,
            SkillGroup skillGroup,
            Instant assignedAt) {
        String bridgeEndpoint =
                operatorBridgeBasePath + "/" + operatorId.value() + "/calls/" + callId + "/audio";
        return new HandoffAssignment(
                callId, ticketId, operatorId, skillGroup, bridgeEndpoint, assignedAt);
    }

    private static String resolveAbandonSourceState(
            Optional<QueuedTicketItem> removedItem,
            Optional<TicketFormFilledEvent> persistedTicket) {
        if (removedItem.isPresent()) {
            return STATE_QUEUED;
        }
        if (persistedTicket.isPresent()) {
            return STATE_ASSIGNED;
        }
        return null;
    }

    private static Map<String, Object> buildAssignmentAuditDetails(
            HandoffAssignment assignment, int priorityScore) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ticketId", assignment.ticketId().toString());
        details.put("skillGroup", assignment.skillGroup().name());
        details.put("bridgeEndpoint", assignment.bridgeEndpoint());
        details.put("priorityScore", priorityScore);
        return Map.copyOf(details);
    }

    private static Map<String, Object> buildQueuedAuditDetails(
            SkillMatchPlan plan, int queuePosition) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("skillGroup", plan.primarySkillGroup().name());
        details.put("priorityScore", plan.priorityScore());
        details.put("queuePosition", queuePosition);
        return Map.copyOf(details);
    }

    private static Map<String, Object> buildCompletedAuditDetails(UUID ticketId, String notes) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ticketId", ticketId.toString());
        details.put("resolutionNotes", notes);
        return Map.copyOf(details);
    }

    private static Map<String, Object> buildAbandonedAuditDetails(UUID ticketId, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ticketId", ticketId.toString());
        details.put("reason", reason);
        return Map.copyOf(details);
    }

    private static String normalizeBridgeBasePath(String path) {
        Objects.requireNonNull(path, "operatorBridgeBasePath cannot be null");
        String trimmed = path.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("operatorBridgeBasePath cannot be blank");
        }
        if (!trimmed.startsWith("/")) {
            throw new IllegalArgumentException(
                    "operatorBridgeBasePath must start with '/': " + trimmed);
        }
        if (trimmed.length() > 1 && trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
