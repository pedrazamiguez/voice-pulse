package com.voicepulse.conversation.domain.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.conversation.domain.event.BargeInDetected;
import com.voicepulse.conversation.domain.event.ConversationDomainEvent;
import com.voicepulse.conversation.domain.event.TurnCompleted;
import com.voicepulse.conversation.domain.exception.IllegalConversationStateException;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ConversationSession Pure Domain Aggregate Tests")
class ConversationSessionTest {

    @Nested
    @DisplayName("Lifecycle Initialization")
    class InitializationTests {

        @Test
        @DisplayName("session starts in Connected state with zero turns")
        void startsInConnectedState() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();

            ConversationSession session = ConversationSession.start(callId, now);

            assertThat(session.callId()).isEqualTo(callId);
            assertThat(session.createdAt()).isEqualTo(now);
            assertThat(session.lastActivityAt()).isEqualTo(now);
            assertThat(session.turnCount()).isZero();
            assertThat(session.isTerminated()).isFalse();
            assertThat(session.disruptionTicket()).isEmpty();
            assertThat(session.state())
                    .isInstanceOf(CallSessionState.Connected.class)
                    .satisfies(
                            s ->
                                    assertThat(((CallSessionState.Connected) s).connectedAt())
                                            .isEqualTo(now));
        }

        @Test
        @DisplayName("null callId is rejected")
        void rejectsNullCallId() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> ConversationSession.start(null, now))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("callId cannot be null");
        }

        @Test
        @DisplayName("null connectedAt is rejected")
        void rejectsNullConnectedAt() {
            CallId callId = CallId.generate();
            assertThatThrownBy(() -> ConversationSession.start(callId, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("connectedAt cannot be null");
        }
    }

    @Nested
    @DisplayName("Happy Path Conversational Progression")
    class HappyPathProgressionTests {

        @Test
        @DisplayName("full conversational lifecycle completes successfully")
        void fullHappyPathLifecycle() {
            CallId callId = CallId.generate();
            Instant t0 = Instant.parse("2026-11-10T09:00:00Z");
            ConversationSession session = ConversationSession.start(callId, t0);

            // 1. Greet
            Instant t1 = t0.plusSeconds(1);
            session.greet(t1);
            assertThat(session.state()).isInstanceOf(CallSessionState.Greeting.class);
            assertThat(session.lastActivityAt()).isEqualTo(t1);

            // 2. Prompt PNR details
            session.promptPassengerDetails(1);
            assertThat(session.state())
                    .isInstanceOf(CallSessionState.CollectingPassengerDetails.class)
                    .satisfies(
                            s ->
                                    assertThat(
                                                    ((CallSessionState.CollectingPassengerDetails)
                                                                    s)
                                                            .attemptCount())
                                            .isEqualTo(1));

            // 3. Turn 1 completed by caller
            Instant t2 = t1.plusSeconds(3);
            session.recordTurn(t2);
            assertThat(session.turnCount()).isEqualTo(1);

            // 4. Acoustic PNR verification prompt
            Instant t3 = t2.plusSeconds(1);
            session.awaitBookingConfirmation("A8F9X2", t3);
            assertThat(session.state())
                    .isInstanceOf(CallSessionState.AwaitingBookingConfirmation.class)
                    .satisfies(
                            s -> {
                                CallSessionState.AwaitingBookingConfirmation ac =
                                        (CallSessionState.AwaitingBookingConfirmation) s;
                                assertThat(ac.transcribedPnr()).isEqualTo("A8F9X2");
                                assertThat(ac.promptedAt()).isEqualTo(t3);
                            });

            // 5. Caller confirms ("Yes") -> Disruption Triage
            Instant t4 = t3.plusSeconds(2);
            session.confirmBooking("A8F9X2", "Andres Pedraza", "SW-104", t4);
            assertThat(session.state())
                    .isInstanceOf(CallSessionState.DisruptionTriage.class)
                    .satisfies(
                            s -> {
                                CallSessionState.DisruptionTriage dt =
                                        (CallSessionState.DisruptionTriage) s;
                                assertThat(dt.pnr()).isEqualTo("A8F9X2");
                                assertThat(dt.customerName()).isEqualTo("Andres Pedraza");
                                assertThat(dt.flightNumber()).isEqualTo("SW-104");
                            });

            // 6. Turn 2 & Ticket Attachment during triage
            Instant t5 = t4.plusSeconds(4);
            session.recordTurn(t5);
            assertThat(session.turnCount()).isEqualTo(2);

            AirlineDisruptionTicket ticket =
                    new AirlineDisruptionTicket(
                            UUID.randomUUID(),
                            callId.value(),
                            "Andres Pedraza",
                            "A8F9X2",
                            "SW-104",
                            DisruptionReason.CANCELED_WEATHER,
                            RebookingPreference.NEXT_AVAILABLE,
                            true,
                            false,
                            "PENDING_HANDOFF",
                            Map.of("party_size", 1),
                            t4,
                            t5);
            session.attachDisruptionTicket(ticket);
            assertThat(session.disruptionTicket()).contains(ticket);

            // 7. Handoff Ready
            session.prepareHandoff("TKT-SW-99214-X", "DISRUPTION_DESK_GOLD");
            assertThat(session.state())
                    .isInstanceOf(CallSessionState.HandoffReady.class)
                    .satisfies(
                            s -> {
                                CallSessionState.HandoffReady hr =
                                        (CallSessionState.HandoffReady) s;
                                assertThat(hr.ticketId()).isEqualTo("TKT-SW-99214-X");
                                assertThat(hr.targetSkillGroup()).isEqualTo("DISRUPTION_DESK_GOLD");
                            });

            // 8. Transferred
            Instant t6 = t5.plusSeconds(2);
            session.transferToOperator("OP-882", t6);
            assertThat(session.state())
                    .isInstanceOf(CallSessionState.Transferred.class)
                    .satisfies(
                            s -> {
                                CallSessionState.Transferred tr = (CallSessionState.Transferred) s;
                                assertThat(tr.operatorId()).isEqualTo("OP-882");
                                assertThat(tr.transferredAt()).isEqualTo(t6);
                            });

            // 9. Terminated
            Instant t7 = t6.plusSeconds(30);
            session.terminate("CALL_COMPLETED_BY_OPERATOR", t7);
            assertThat(session.isTerminated()).isTrue();
            assertThat(session.state())
                    .isInstanceOf(CallSessionState.Terminated.class)
                    .satisfies(
                            s -> {
                                CallSessionState.Terminated te = (CallSessionState.Terminated) s;
                                assertThat(te.reason()).isEqualTo("CALL_COMPLETED_BY_OPERATOR");
                                assertThat(te.terminatedAt()).isEqualTo(t7);
                            });

            // 10. Verify accumulated domain events
            List<ConversationDomainEvent> events = session.pollDomainEvents();
            assertThat(events).hasSize(9); // 7 state transitions + 2 turn completions
            assertThat(session.pollDomainEvents()).isEmpty(); // atomic queue drain
        }
    }

    @Nested
    @DisplayName("Negative Confirmation & Retry Loop")
    class RetryLoopTests {

        @Test
        @DisplayName(
                "negative confirmation returns session to collecting details with incremented"
                        + " attempt")
        void negativeConfirmationRetryLoop() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            ConversationSession session = ConversationSession.start(callId, now);

            session.greet(now.plusMillis(100));
            session.promptPassengerDetails(1);
            session.awaitBookingConfirmation("WRONG1", now.plusMillis(500));

            // Caller says "No, that's wrong" -> return to CollectingPassengerDetails(2)
            session.promptPassengerDetails(2);
            assertThat(session.state())
                    .isInstanceOf(CallSessionState.CollectingPassengerDetails.class)
                    .satisfies(
                            s ->
                                    assertThat(
                                                    ((CallSessionState.CollectingPassengerDetails)
                                                                    s)
                                                            .attemptCount())
                                            .isEqualTo(2));

            // Prompt new confirmation
            session.awaitBookingConfirmation("RIGHT1", now.plusMillis(900));
            session.confirmBooking("RIGHT1", "Passenger Name", "SW-200", now.plusSeconds(1));

            assertThat(session.state()).isInstanceOf(CallSessionState.DisruptionTriage.class);
        }
    }

    @Nested
    @DisplayName("Barge-In and Turn Events")
    class BargeInAndTurnTests {

        @Test
        @DisplayName("recording barge-in stages BargeInDetected event")
        void recordsBargeIn() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            ConversationSession session = ConversationSession.start(callId, now);

            session.greet(now);
            Instant bargeInTime = now.plusMillis(300);
            session.recordBargeIn(bargeInTime);

            List<ConversationDomainEvent> events = session.pollDomainEvents();
            assertThat(events)
                    .hasSize(2) // 1 CallStateTransitioned + 1 BargeInDetected
                    .anyMatch(
                            e ->
                                    e instanceof BargeInDetected bd
                                            && bd.callId().equals(callId)
                                            && bd.currentState()
                                                    instanceof CallSessionState.Greeting
                                            && bd.occurredAt().equals(bargeInTime));
        }

        @Test
        @DisplayName("recording turn stages TurnCompleted event")
        void recordsTurn() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            ConversationSession session = ConversationSession.start(callId, now);

            session.greet(now);
            Instant turnTime = now.plusMillis(500);
            session.recordTurn(turnTime);

            List<ConversationDomainEvent> events = session.pollDomainEvents();
            assertThat(events)
                    .hasSize(2)
                    .anyMatch(
                            e ->
                                    e instanceof TurnCompleted tc
                                            && tc.callId().equals(callId)
                                            && tc.turnNumber() == 1
                                            && tc.occurredAt().equals(turnTime));
        }
    }

    @Nested
    @DisplayName("Ticket Attachment Invariants")
    class TicketAttachmentTests {

        @Test
        @DisplayName("rejects ticket with mismatched callId")
        void rejectsMismatchedTicketCallId() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            ConversationSession session = ConversationSession.start(callId, now);

            AirlineDisruptionTicket mismatchedTicket =
                    new AirlineDisruptionTicket(
                            UUID.randomUUID(),
                            UUID.randomUUID(), // different callId
                            "Andres",
                            "A8F9X2",
                            "SW-104",
                            DisruptionReason.CANCELED_WEATHER,
                            RebookingPreference.NEXT_AVAILABLE,
                            true,
                            false,
                            "PENDING",
                            Map.of(),
                            now,
                            now);

            assertThatThrownBy(() -> session.attachDisruptionTicket(mismatchedTicket))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match session callId");
        }
    }

    @Nested
    @DisplayName("Illegal State Transition Guardrails")
    class GuardrailTests {

        @Test
        @DisplayName("cannot transition directly from Connected to DisruptionTriage")
        void rejectsConnectedToTriage() {
            CallId callId = CallId.generate();
            ConversationSession session = ConversationSession.start(callId, Instant.now());

            assertThatThrownBy(
                            () ->
                                    session.confirmBooking(
                                            "A8F9X2", "Passenger", "SW-104", Instant.now()))
                    .isInstanceOf(IllegalConversationStateException.class)
                    .hasMessageContaining(
                            "Cannot transition to DisruptionTriage from state: Connected");
        }

        @Test
        @DisplayName("cannot transition directly from Greeting to Transferred")
        void rejectsGreetingToTransferred() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            ConversationSession session = ConversationSession.start(callId, now);
            session.greet(now);

            assertThatThrownBy(() -> session.transferToOperator("OP-1", now))
                    .isInstanceOf(IllegalConversationStateException.class)
                    .hasMessageContaining("Cannot transition to Transferred from state: Greeting");
        }

        @Test
        @DisplayName("cannot transition directly from CollectingPassengerDetails to HandoffReady")
        void rejectsCollectingToHandoff() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            ConversationSession session = ConversationSession.start(callId, now);
            session.greet(now);
            session.promptPassengerDetails(1);

            assertThatThrownBy(() -> session.prepareHandoff("TKT-1", "DESK"))
                    .isInstanceOf(IllegalConversationStateException.class)
                    .hasMessageContaining(
                            "Cannot transition to HandoffReady from state:"
                                    + " CollectingPassengerDetails");
        }
    }

    @Nested
    @DisplayName("Termination Guardrails & Idempotence")
    class TerminationTests {

        @Test
        @DisplayName("terminate is idempotent on already terminated session")
        void terminationIsIdempotent() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            ConversationSession session = ConversationSession.start(callId, now);

            session.terminate("CALLER_HANGUP", now);
            assertThat(session.isTerminated()).isTrue();

            // Calling terminate again should be a safe no-op
            session.terminate("CALLER_HANGUP", now.plusSeconds(1));
            assertThat(session.isTerminated()).isTrue();
        }

        @Test
        @DisplayName("mutations on terminated session throw IllegalConversationStateException")
        void rejectsMutationsOnTerminatedSession() {
            CallId callId = CallId.generate();
            Instant now = Instant.now();
            ConversationSession session = ConversationSession.start(callId, now);
            session.terminate("HANGUP", now);

            assertThatThrownBy(() -> session.greet(now))
                    .isInstanceOf(IllegalConversationStateException.class)
                    .hasMessageContaining(
                            "Cannot execute 'greet' on terminated conversation session");

            assertThatThrownBy(() -> session.recordTurn(now))
                    .isInstanceOf(IllegalConversationStateException.class)
                    .hasMessageContaining(
                            "Cannot execute 'recordTurn' on terminated conversation session");

            assertThatThrownBy(() -> session.recordBargeIn(now))
                    .isInstanceOf(IllegalConversationStateException.class)
                    .hasMessageContaining(
                            "Cannot execute 'recordBargeIn' on terminated conversation session");
        }
    }
}
