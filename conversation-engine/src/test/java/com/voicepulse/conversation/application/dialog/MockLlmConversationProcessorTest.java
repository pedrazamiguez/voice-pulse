package com.voicepulse.conversation.application.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.conversation.domain.linguistics.TurnBrevityValidator;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MockLlmConversationProcessor Unit Tests")
class MockLlmConversationProcessorTest {

    private ConversationResponseGenerator responseGenerator;
    private MockLlmConversationProcessor processor;
    private CallId callId;
    private Instant now;

    @BeforeEach
    void setUp() {
        responseGenerator = new ConversationResponseGenerator();
        processor = new MockLlmConversationProcessor(responseGenerator);
        callId = CallId.generate();
        now = Instant.now();
    }

    @Test
    @DisplayName(
            "Progresses full multi-turn disruption triage dialogue deterministically to"
                    + " HandoffReady")
    void fullMultiTurnDialogue_progressesDeterministically() {
        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);

        // Turn 1: Caller provides PNR with NATO phonetic words and conversational filler
        DialogTurnResult turn1 =
                processor.processTurn(
                        session,
                        "Uh, yes, my booking reference is alpha eight, foxtrot nine, x-ray two.",
                        now);

        assertThat(turn1.targetState())
                .isInstanceOf(CallSessionState.AwaitingBookingConfirmation.class);
        assertThat(turn1.responseText()).contains("Alpha-8-Foxtrot-9-Xray-2");
        assertThat(turn1.responseText()).contains("Is that correct?");
        assertThat(TurnBrevityValidator.isValid(turn1.responseText())).isTrue();
        assertThat(turn1.disruptionTicket()).isEmpty();
        assertThat(session.state())
                .isInstanceOf(CallSessionState.AwaitingBookingConfirmation.class);

        // Turn 2: Caller confirms booking reference acoustically
        DialogTurnResult turn2 = processor.processTurn(session, "Yes, that's correct.", now);

        assertThat(turn2.targetState()).isInstanceOf(CallSessionState.DisruptionTriage.class);
        assertThat(turn2.responseText()).contains("SW-104");
        assertThat(turn2.responseText()).contains("weather");
        assertThat(TurnBrevityValidator.isValid(turn2.responseText())).isTrue();
        assertThat(turn2.disruptionTicket()).isEmpty();
        assertThat(session.state()).isInstanceOf(CallSessionState.DisruptionTriage.class);

        // Turn 3: Caller requests next available flight and hotel accommodation
        DialogTurnResult turn3 =
                processor.processTurn(
                        session,
                        "I need a hotel room for tonight and the next available flight tomorrow.",
                        now);

        assertThat(turn3.targetState()).isInstanceOf(CallSessionState.HandoffReady.class);
        assertThat(turn3.responseText()).contains("hotel request");
        assertThat(turn3.responseText()).contains("priority disruption team");
        assertThat(TurnBrevityValidator.isValid(turn3.responseText())).isTrue();
        assertThat(turn3.disruptionTicket()).isPresent();

        AirlineDisruptionTicket ticket = turn3.disruptionTicket().get();
        assertThat(ticket.callId()).isEqualTo(callId.value());
        assertThat(ticket.pnr()).isEqualTo("A8F9X2");
        assertThat(ticket.passengerName()).isEqualTo("Andres");
        assertThat(ticket.originalFlightNumber()).isEqualTo("SW-104");
        assertThat(ticket.disruptionReason()).isEqualTo(DisruptionReason.CANCELED_WEATHER);
        assertThat(ticket.rebookingPreference()).isEqualTo(RebookingPreference.NEXT_AVAILABLE);
        assertThat(ticket.hotelRequired()).isTrue();
        assertThat(ticket.specialAssistance()).isFalse();
        assertThat(ticket.status()).isEqualTo("COMPILED");
        assertThat(session.state()).isInstanceOf(CallSessionState.HandoffReady.class);
        assertThat(session.disruptionTicket()).contains(ticket);
    }

    @Test
    @DisplayName("Handles negative acoustic confirmation and reprompts for PNR")
    void negativeConfirmation_revertsToCollectingPassengerDetails() {
        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);

        // PNR parsed
        processor.processTurn(session, "A8F9X2", now);
        assertThat(session.state())
                .isInstanceOf(CallSessionState.AwaitingBookingConfirmation.class);

        // Caller denies confirmation
        DialogTurnResult result = processor.processTurn(session, "No, that is incorrect.", now);

        assertThat(result.targetState())
                .isInstanceOf(CallSessionState.CollectingPassengerDetails.class);
        assertThat(result.responseText()).contains("booking reference");
        assertThat(TurnBrevityValidator.isValid(result.responseText())).isTrue();
        assertThat(session.state()).isInstanceOf(CallSessionState.CollectingPassengerDetails.class);
    }

    @Test
    @DisplayName("Reprompts when PNR cannot be detected in caller speech")
    void unparseablePnr_repromptsCaller() {
        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);

        DialogTurnResult result =
                processor.processTurn(session, "Hello, can someone help me?", now);

        assertThat(result.targetState())
                .isInstanceOf(CallSessionState.CollectingPassengerDetails.class);
        assertThat(result.responseText()).contains("booking reference");
        assertThat(TurnBrevityValidator.isValid(result.responseText())).isTrue();
        assertThat(session.state()).isInstanceOf(CallSessionState.CollectingPassengerDetails.class);
    }

    @Test
    @DisplayName("Compiles ticket with REFUND_REQUESTED preference")
    void disruptionTriage_refundRequested_compilesRefundTicket() {
        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);
        processor.processTurn(session, "A8F9X2", now);
        processor.processTurn(session, "Yes", now);

        DialogTurnResult result =
                processor.processTurn(
                        session, "I want a full refund for this flight cancellation.", now);

        assertThat(result.targetState()).isInstanceOf(CallSessionState.HandoffReady.class);
        assertThat(result.responseText()).contains("refund request");
        assertThat(result.disruptionTicket()).isPresent();

        AirlineDisruptionTicket ticket = result.disruptionTicket().get();
        assertThat(ticket.rebookingPreference()).isEqualTo(RebookingPreference.REFUND_REQUESTED);
        assertThat(ticket.hotelRequired()).isFalse();
    }

    @Test
    @DisplayName("Detects wheelchair assistance requirement in disruption triage")
    void disruptionTriage_specialAssistance_flagsSpecialAssistance() {
        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);
        processor.processTurn(session, "A8F9X2", now);
        processor.processTurn(session, "Correct", now);

        DialogTurnResult result =
                processor.processTurn(
                        session,
                        "Please rebook me on next flight, and I need wheelchair assistance.",
                        now);

        assertThat(result.disruptionTicket()).isPresent();
        AirlineDisruptionTicket ticket = result.disruptionTicket().get();
        assertThat(ticket.specialAssistance()).isTrue();
        assertThat(ticket.rebookingPreference()).isEqualTo(RebookingPreference.NEXT_AVAILABLE);
    }

    @Test
    @DisplayName("Automatically transitions session in Connected state to Greeting on initial turn")
    void connectedSession_transitionsToGreeting() {
        ConversationSession session = ConversationSession.start(callId, now);

        DialogTurnResult result = processor.processTurn(session, "hello", now);

        assertThat(result.targetState()).isInstanceOf(CallSessionState.Greeting.class);
        assertThat(result.responseText()).contains("Welcome to the Airline Disruption Hotline");
        assertThat(session.state()).isInstanceOf(CallSessionState.Greeting.class);
    }

    @Test
    @DisplayName("Throws NullPointerException on null parameters")
    void processTurn_nullParameters_throwsException() {
        ConversationSession session = ConversationSession.start(callId, now);

        assertThatThrownBy(() -> processor.processTurn(null, "hello", now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("session");

        assertThatThrownBy(() -> processor.processTurn(session, null, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("utterance");

        assertThatThrownBy(() -> processor.processTurn(session, "hello", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");
    }
}
