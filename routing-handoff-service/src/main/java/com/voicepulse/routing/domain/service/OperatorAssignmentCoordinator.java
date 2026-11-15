package com.voicepulse.routing.domain.service;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.model.SkillMatchPlan;
import com.voicepulse.routing.domain.ports.inbound.ProcessTicketIntentUseCase;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure Java 21 domain coordinator implementing {@link ProcessTicketIntentUseCase} to match
 * finalized disruption tickets to available skill-group operators or enqueue them for priority
 * handoff when capacity is busy.
 */
public class OperatorAssignmentCoordinator implements ProcessTicketIntentUseCase {

    private final SkillGroupMatchingPolicy matchingPolicy;
    private final OperatorPresencePort operatorPresencePort;
    private final PriorityCallQueuePort priorityCallQueuePort;
    private final HandoffPersistenceCoordinator persistenceCoordinator;
    private final OperatorRerouteDispatcher dispatcher;
    private final Clock clock;

    public OperatorAssignmentCoordinator(
            SkillGroupMatchingPolicy matchingPolicy,
            OperatorPresencePort operatorPresencePort,
            PriorityCallQueuePort priorityCallQueuePort,
            HandoffPersistenceCoordinator persistenceCoordinator,
            OperatorRerouteDispatcher dispatcher,
            Clock clock) {
        this.matchingPolicy =
                Objects.requireNonNull(matchingPolicy, "matchingPolicy cannot be null");
        this.operatorPresencePort =
                Objects.requireNonNull(operatorPresencePort, "operatorPresencePort cannot be null");
        this.priorityCallQueuePort =
                Objects.requireNonNull(
                        priorityCallQueuePort, "priorityCallQueuePort cannot be null");
        this.persistenceCoordinator =
                Objects.requireNonNull(
                        persistenceCoordinator, "persistenceCoordinator cannot be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    @Override
    public HandoffLifecycleState processTicketIntent(TicketFormFilledEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        SkillMatchPlan plan = matchingPolicy.evaluate(event);
        Instant now = clock.instant();

        return tryAssignAvailableOperator(event, plan, now)
                .orElseGet(() -> enqueueUnmatchedTicket(event, plan, now));
    }

    private Optional<HandoffLifecycleState> tryAssignAvailableOperator(
            TicketFormFilledEvent event, SkillMatchPlan plan, Instant now) {
        for (SkillGroup candidateSkill : plan.candidateOrder()) {
            for (OperatorPresence candidate :
                    operatorPresencePort.findAvailableBySkillGroup(candidateSkill)) {
                Optional<HandoffLifecycleState> assigned =
                        tryClaimCandidate(event, plan, candidate, candidateSkill, now);
                if (assigned.isPresent()) {
                    return assigned;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<HandoffLifecycleState> tryClaimCandidate(
            TicketFormFilledEvent event,
            SkillMatchPlan plan,
            OperatorPresence candidate,
            SkillGroup matchedSkill,
            Instant now) {
        if (candidate == null
                || !candidate.status().canAcceptCall()
                || !operatorPresencePort.tryClaimOperator(
                        candidate.operatorId(), event.callId(), now)) {
            return Optional.empty();
        }
        HandoffAssignment assignment =
                dispatcher.createAssignment(
                        event.callId(),
                        event.ticketId(),
                        candidate.operatorId(),
                        matchedSkill,
                        now);
        HandoffLifecycleState.Assigned assignedState =
                new HandoffLifecycleState.Assigned(
                        event.callId(),
                        event.ticketId(),
                        candidate.operatorId(),
                        matchedSkill,
                        now);
        persistenceCoordinator.saveImmediateAssignment(event, plan, assignment, assignedState, now);
        dispatcher.dispatchAssignment(assignment, event, assignedState, now);
        return Optional.of(assignedState);
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

        persistenceCoordinator.saveQueuedTicket(event, plan, queuedState, queuePosition, now);
        dispatcher.broadcastQueueDepth(
                plan.primarySkillGroup(),
                priorityCallQueuePort.queueDepth(plan.primarySkillGroup()));
        dispatcher.notifyLifecycleState(queuedState);
        return queuedState;
    }
}
