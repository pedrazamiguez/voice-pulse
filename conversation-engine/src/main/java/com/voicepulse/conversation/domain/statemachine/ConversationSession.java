package com.voicepulse.conversation.domain.statemachine;

import com.voicepulse.conversation.domain.event.BargeInDetected;
import com.voicepulse.conversation.domain.event.CallStateTransitioned;
import com.voicepulse.conversation.domain.event.ConversationDomainEvent;
import com.voicepulse.conversation.domain.event.TurnCompleted;
import com.voicepulse.conversation.domain.exception.IllegalConversationStateException;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.vad.TurnPhase;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure domain aggregate root orchestrating conversational state transitions, turn counters,
 * barge-in detection, and domain event accumulation for the SkyWay Airlines Disruption Hotline.
 *
 * <p>Enforces strict transition guards per RFC-0003 and disruption-hotline-state-machine.md with
 * zero framework contamination.
 */
public class ConversationSession {

    private final Object lock = new Object();
    private final CallId callId;
    private final Instant createdAt;
    private final List<ConversationDomainEvent> domainEvents;
    private CallSessionState state;
    private TurnPhase turnPhase;
    private int turnCount;
    private Instant lastActivityAt;
    private AirlineDisruptionTicket disruptionTicket;

    private ConversationSession(CallId callId, Instant connectedAt) {
        this.callId = Objects.requireNonNull(callId, "callId cannot be null");
        this.createdAt = Objects.requireNonNull(connectedAt, "connectedAt cannot be null");
        this.lastActivityAt = connectedAt;
        this.state = new CallSessionState.Connected(connectedAt);
        this.turnPhase = TurnPhase.LISTENING;
        this.turnCount = 0;
        this.domainEvents = new ArrayList<>();
    }

    /** Factory method creating a new conversation session in {@link CallSessionState.Connected}. */
    public static ConversationSession start(CallId callId, Instant connectedAt) {
        return new ConversationSession(callId, connectedAt);
    }

    /**
     * Transitions session to {@link CallSessionState.Greeting}.
     *
     * @param greetedAt timestamp of greeting prompt
     * @throws IllegalConversationStateException if current state is not Connected
     */
    public void greet(Instant greetedAt) {
        synchronized (lock) {
            Objects.requireNonNull(greetedAt, "greetedAt cannot be null");
            assertNotTerminated("greet");

            if (!(this.state instanceof CallSessionState.Connected)) {
                throw new IllegalConversationStateException(
                        "Cannot transition to Greeting from state: " + this.state);
            }

            transitionTo(new CallSessionState.Greeting(greetedAt), greetedAt);
        }
    }

    /**
     * Transitions session to {@link CallSessionState.CollectingPassengerDetails}.
     *
     * @param attemptCount current collection attempt number (>= 1)
     * @throws IllegalConversationStateException if current state does not allow detail collection
     */
    public void promptPassengerDetails(int attemptCount) {
        synchronized (lock) {
            assertNotTerminated("promptPassengerDetails");

            if (!(this.state instanceof CallSessionState.Greeting)
                    && !(this.state instanceof CallSessionState.CollectingPassengerDetails)
                    && !(this.state instanceof CallSessionState.AwaitingBookingConfirmation)) {
                throw new IllegalConversationStateException(
                        "Cannot transition to CollectingPassengerDetails from state: "
                                + this.state);
            }

            Instant now = this.lastActivityAt;
            transitionTo(new CallSessionState.CollectingPassengerDetails(attemptCount), now);
        }
    }

    /**
     * Transitions session to {@link CallSessionState.AwaitingBookingConfirmation}.
     *
     * @param transcribedPnr candidate 6-character PNR string
     * @param promptedAt timestamp when acoustic prompt was issued
     * @throws IllegalConversationStateException if current state is not CollectingPassengerDetails
     */
    public void awaitBookingConfirmation(String transcribedPnr, Instant promptedAt) {
        synchronized (lock) {
            Objects.requireNonNull(transcribedPnr, "transcribedPnr cannot be null");
            Objects.requireNonNull(promptedAt, "promptedAt cannot be null");
            assertNotTerminated("awaitBookingConfirmation");

            if (!(this.state instanceof CallSessionState.CollectingPassengerDetails)) {
                throw new IllegalConversationStateException(
                        "Cannot transition to AwaitingBookingConfirmation from state: "
                                + this.state);
            }

            transitionTo(
                    new CallSessionState.AwaitingBookingConfirmation(transcribedPnr, promptedAt),
                    promptedAt);
        }
    }

    /**
     * Transitions session to {@link CallSessionState.DisruptionTriage} upon acoustic verification.
     *
     * @param pnr verified 6-character PNR
     * @param customerName passenger full name
     * @param flightNumber disrupted flight number
     * @param triagedAt timestamp of triage commencement
     * @throws IllegalConversationStateException if current state is not AwaitingBookingConfirmation
     */
    public void confirmBooking(
            String pnr, String customerName, String flightNumber, Instant triagedAt) {
        synchronized (lock) {
            Objects.requireNonNull(pnr, "pnr cannot be null");
            Objects.requireNonNull(customerName, "customerName cannot be null");
            Objects.requireNonNull(flightNumber, "flightNumber cannot be null");
            Objects.requireNonNull(triagedAt, "triagedAt cannot be null");
            assertNotTerminated("confirmBooking");

            if (!(this.state instanceof CallSessionState.AwaitingBookingConfirmation)) {
                throw new IllegalConversationStateException(
                        "Cannot transition to DisruptionTriage from state: " + this.state);
            }

            transitionTo(
                    new CallSessionState.DisruptionTriage(
                            pnr, customerName, flightNumber, triagedAt),
                    triagedAt);
        }
    }

    /**
     * Associates a compiled disruption ticket with this conversation session.
     *
     * @param ticket immutable disruption ticket
     * @throws IllegalConversationStateException if session is terminated
     * @throws IllegalArgumentException if ticket callId does not match session
     */
    public void attachDisruptionTicket(AirlineDisruptionTicket ticket) {
        synchronized (lock) {
            Objects.requireNonNull(ticket, "ticket cannot be null");
            assertNotTerminated("attachDisruptionTicket");

            if (!this.callId.value().equals(ticket.callId())) {
                throw new IllegalArgumentException(
                        "Ticket callId "
                                + ticket.callId()
                                + " does not match session callId "
                                + this.callId.value());
            }

            this.disruptionTicket = ticket;
            this.lastActivityAt = ticket.updatedAt();
        }
    }

    /**
     * Transitions session to {@link CallSessionState.HandoffReady}.
     *
     * @param ticketId compiled ticket identifier
     * @param targetSkillGroup operator queue destination
     * @throws IllegalConversationStateException if current state is not DisruptionTriage
     */
    public void prepareHandoff(String ticketId, String targetSkillGroup) {
        synchronized (lock) {
            Objects.requireNonNull(ticketId, "ticketId cannot be null");
            Objects.requireNonNull(targetSkillGroup, "targetSkillGroup cannot be null");
            assertNotTerminated("prepareHandoff");

            if (!(this.state instanceof CallSessionState.DisruptionTriage)) {
                throw new IllegalConversationStateException(
                        "Cannot transition to HandoffReady from state: " + this.state);
            }

            transitionTo(
                    new CallSessionState.HandoffReady(ticketId, targetSkillGroup),
                    this.lastActivityAt);
        }
    }

    /**
     * Transitions session to {@link CallSessionState.Transferred}.
     *
     * @param operatorId assigned human operator identifier
     * @param transferredAt timestamp of transfer
     * @throws IllegalConversationStateException if current state is not HandoffReady
     */
    public void transferToOperator(String operatorId, Instant transferredAt) {
        synchronized (lock) {
            Objects.requireNonNull(operatorId, "operatorId cannot be null");
            Objects.requireNonNull(transferredAt, "transferredAt cannot be null");
            assertNotTerminated("transferToOperator");

            if (!(this.state instanceof CallSessionState.HandoffReady)) {
                throw new IllegalConversationStateException(
                        "Cannot transition to Transferred from state: " + this.state);
            }

            transitionTo(
                    new CallSessionState.Transferred(operatorId, transferredAt), transferredAt);
        }
    }

    /**
     * Concludes or aborts the session, transitioning to {@link CallSessionState.Terminated}.
     *
     * <p>Idempotent if session is already terminated.
     *
     * @param reason cancellation or completion reason
     * @param terminatedAt timestamp of termination
     */
    public void terminate(String reason, Instant terminatedAt) {
        synchronized (lock) {
            Objects.requireNonNull(reason, "reason cannot be null");
            Objects.requireNonNull(terminatedAt, "terminatedAt cannot be null");

            if (this.state instanceof CallSessionState.Terminated) {
                return; // Idempotent
            }

            transitionTo(new CallSessionState.Terminated(reason, terminatedAt), terminatedAt);
        }
    }

    /**
     * Records a completed conversational turn, incrementing turn count and emitting {@link
     * TurnCompleted}.
     *
     * @param timestamp timestamp when turn completed
     * @throws IllegalConversationStateException if session is terminated
     */
    public void recordTurn(Instant timestamp) {
        synchronized (lock) {
            Objects.requireNonNull(timestamp, "timestamp cannot be null");
            assertNotTerminated("recordTurn");

            this.turnCount++;
            this.turnPhase = TurnPhase.LISTENING;
            this.lastActivityAt = timestamp;
            this.domainEvents.add(
                    new TurnCompleted(this.callId, this.turnCount, this.state, timestamp));
        }
    }

    /**
     * Marks the commencement of an active agent speech playback turn.
     *
     * @param timestamp timestamp when agent speech started
     * @throws IllegalConversationStateException if session is terminated
     */
    public void startBotTurn(Instant timestamp) {
        synchronized (lock) {
            Objects.requireNonNull(timestamp, "timestamp cannot be null");
            assertNotTerminated("startBotTurn");

            this.turnPhase = TurnPhase.BOT_SPEAKING;
            this.lastActivityAt = timestamp;
        }
    }

    /**
     * Records a detected caller barge-in during bot playback, aborting the active bot turn,
     * resetting turn phase to {@link TurnPhase#LISTENING}, and emitting {@link BargeInDetected}.
     *
     * <p>If currently in {@link CallSessionState.Greeting}, transitions to {@link
     * CallSessionState.CollectingPassengerDetails} to immediately capture caller input.
     *
     * @param timestamp timestamp when barge-in was detected
     * @throws IllegalConversationStateException if session is terminated
     */
    public void recordBargeIn(Instant timestamp) {
        synchronized (lock) {
            Objects.requireNonNull(timestamp, "timestamp cannot be null");
            assertNotTerminated("recordBargeIn");

            CallSessionState stateAtBargeIn = this.state;
            this.turnPhase = TurnPhase.LISTENING;
            this.lastActivityAt = timestamp;
            this.domainEvents.add(new BargeInDetected(this.callId, stateAtBargeIn, timestamp));

            if (stateAtBargeIn instanceof CallSessionState.Greeting) {
                transitionTo(new CallSessionState.CollectingPassengerDetails(1), timestamp);
            }
        }
    }

    /**
     * Atomically extracts and drains all uncommitted domain events.
     *
     * @return immutable copy of uncommitted domain events
     */
    public List<ConversationDomainEvent> pollDomainEvents() {
        synchronized (lock) {
            List<ConversationDomainEvent> events = List.copyOf(this.domainEvents);
            this.domainEvents.clear();
            return events;
        }
    }

    public CallId callId() {
        return this.callId;
    }

    public Instant createdAt() {
        return this.createdAt;
    }

    public CallSessionState state() {
        synchronized (lock) {
            return this.state;
        }
    }

    public TurnPhase turnPhase() {
        synchronized (lock) {
            return this.turnPhase;
        }
    }

    public int turnCount() {
        synchronized (lock) {
            return this.turnCount;
        }
    }

    public Instant lastActivityAt() {
        synchronized (lock) {
            return this.lastActivityAt;
        }
    }

    public Optional<AirlineDisruptionTicket> disruptionTicket() {
        synchronized (lock) {
            return Optional.ofNullable(this.disruptionTicket);
        }
    }

    public boolean isTerminated() {
        synchronized (lock) {
            return this.state instanceof CallSessionState.Terminated;
        }
    }

    private void transitionTo(CallSessionState newState, Instant timestamp) {
        CallSessionState oldState = this.state;
        this.state = newState;
        this.lastActivityAt = timestamp;
        this.domainEvents.add(
                new CallStateTransitioned(this.callId, oldState, newState, timestamp));
    }

    private void assertNotTerminated(String operation) {
        if (this.state instanceof CallSessionState.Terminated) {
            throw new IllegalConversationStateException(
                    "Cannot execute '"
                            + operation
                            + "' on terminated conversation session: "
                            + this.callId);
        }
    }
}
