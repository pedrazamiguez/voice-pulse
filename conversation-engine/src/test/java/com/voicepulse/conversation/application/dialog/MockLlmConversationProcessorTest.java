package com.voicepulse.conversation.application.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.conversation.domain.linguistics.TurnBrevityValidator;
import com.voicepulse.conversation.domain.model.ActiveItinerary;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.conversation.domain.model.DialogTurnResult;
import com.voicepulse.conversation.domain.model.FrequentFlyerTier;
import com.voicepulse.conversation.domain.model.OperatorHandoffReceipt;
import com.voicepulse.conversation.domain.model.TicketSubmissionReceipt;
import com.voicepulse.conversation.domain.ports.outbound.CustomerLookupPort;
import com.voicepulse.conversation.domain.ports.outbound.DisruptionTicketPort;
import com.voicepulse.conversation.domain.ports.outbound.OperatorRoutingPort;
import com.voicepulse.conversation.domain.ports.outbound.TicketIntentPublisherPort;
import com.voicepulse.conversation.domain.service.SkillGroupRoutingPolicy;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MockLlmConversationProcessor Unit Tests")
class MockLlmConversationProcessorTest {

    @Mock private CustomerLookupPort customerLookupPort;
    @Mock private DisruptionTicketPort disruptionTicketPort;
    @Mock private OperatorRoutingPort operatorRoutingPort;
    @Mock private TicketIntentPublisherPort ticketIntentPublisherPort;

    @Captor private ArgumentCaptor<AirlineDisruptionTicket> ticketCaptor;
    @Captor private ArgumentCaptor<TicketFormFilledEvent> eventCaptor;

    private ConversationResponseGenerator responseGenerator;
    private DisruptionTriageCoordinator triageCoordinator;
    private MockLlmConversationProcessor processor;
    private CallId callId;
    private Instant now;
    private CustomerProfile goldWeatherProfile;

    @BeforeEach
    void setUp() {
        responseGenerator = new ConversationResponseGenerator();
        triageCoordinator =
                new DisruptionTriageCoordinator(
                        customerLookupPort,
                        disruptionTicketPort,
                        operatorRoutingPort,
                        ticketIntentPublisherPort,
                        new SkillGroupRoutingPolicy());
        processor = new MockLlmConversationProcessor(responseGenerator, triageCoordinator);
        callId = CallId.generate();
        now = Instant.now();

        ActiveItinerary itinerary =
                Instancio.of(ActiveItinerary.class)
                        .set(field(ActiveItinerary::flightNumber), new FlightNumber("SW-104"))
                        .set(
                                field(ActiveItinerary::disruptionReason),
                                DisruptionReason.CANCELED_WEATHER)
                        .create();
        goldWeatherProfile =
                Instancio.of(CustomerProfile.class)
                        .set(field(CustomerProfile::pnr), new Pnr("A8F9X2"))
                        .set(field(CustomerProfile::customerName), "Andres")
                        .set(field(CustomerProfile::frequentFlyerTier), FrequentFlyerTier.GOLD)
                        .set(field(CustomerProfile::activeItinerary), itinerary)
                        .create();

        lenient()
                .when(customerLookupPort.lookupCustomerByPnr(new Pnr("A8F9X2")))
                .thenReturn(Optional.of(goldWeatherProfile));

        TicketSubmissionReceipt submissionReceipt =
                Instancio.of(TicketSubmissionReceipt.class)
                        .set(field(TicketSubmissionReceipt::pnr), new Pnr("A8F9X2"))
                        .set(field(TicketSubmissionReceipt::status), "FINALIZED")
                        .create();
        lenient()
                .when(disruptionTicketPort.finalizeTicket(any(AirlineDisruptionTicket.class)))
                .thenReturn(submissionReceipt);

        OperatorHandoffReceipt handoffReceipt =
                Instancio.of(OperatorHandoffReceipt.class)
                        .set(field(OperatorHandoffReceipt::callId), callId)
                        .set(field(OperatorHandoffReceipt::department), "DISRUPTION_DESK_GOLD")
                        .set(field(OperatorHandoffReceipt::status), "QUEUED")
                        .create();
        lenient()
                .when(
                        operatorRoutingPort.transferToHumanOperator(
                                eq(callId), any(String.class), any(UUID.class)))
                .thenReturn(handoffReceipt);
    }

    @Test
    @DisplayName(
            "Progresses full multi-turn disruption triage dialogue with MCP and Kafka"
                    + " orchestration")
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

        // Turn 2: Caller confirms booking reference acoustically -> CRM lookup
        DialogTurnResult turn2 = processor.processTurn(session, "Yes, that's correct.", now);

        verify(customerLookupPort).lookupCustomerByPnr(new Pnr("A8F9X2"));
        assertThat(turn2.targetState()).isInstanceOf(CallSessionState.DisruptionTriage.class);
        assertThat(turn2.responseText()).contains("SW-104");
        assertThat(turn2.responseText()).contains("weather");
        assertThat(TurnBrevityValidator.isValid(turn2.responseText())).isTrue();
        assertThat(turn2.disruptionTicket()).isEmpty();
        assertThat(session.state()).isInstanceOf(CallSessionState.DisruptionTriage.class);

        // Turn 3: Caller requests next available flight and hotel accommodation -> MCP + Kafka
        DialogTurnResult turn3 =
                processor.processTurn(
                        session,
                        "I need a hotel room for tonight and the next available flight tomorrow.",
                        now);

        assertThat(turn3.targetState())
                .isInstanceOf(CallSessionState.HandoffReady.class)
                .satisfies(
                        state -> {
                            CallSessionState.HandoffReady handoff =
                                    (CallSessionState.HandoffReady) state;
                            assertThat(handoff.targetSkillGroup())
                                    .isEqualTo("DISRUPTION_DESK_GOLD");
                        });
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

        verify(disruptionTicketPort).finalizeTicket(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().ticketId()).isEqualTo(ticket.ticketId());

        verify(operatorRoutingPort)
                .transferToHumanOperator(callId, "DISRUPTION_DESK_GOLD", ticket.ticketId());

        verify(ticketIntentPublisherPort).publishTicketFilled(eventCaptor.capture());
        TicketFormFilledEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.callId()).isEqualTo(callId);
        assertThat(publishedEvent.ticketId()).isEqualTo(ticket.ticketId());
        assertThat(publishedEvent.targetSkillGroup()).isEqualTo("DISRUPTION_DESK_GOLD");
    }

    @Test
    @DisplayName(
            "Transitions from AwaitingBookingConfirmation back to CollectingPassengerDetails when"
                    + " CRM lookup returns empty")
    void pnrNotFoundInCrm_revertsToCollectingPassengerDetails() {
        when(customerLookupPort.lookupCustomerByPnr(new Pnr("Z9Z9Z9")))
                .thenReturn(Optional.empty());

        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);
        processor.processTurn(session, "Z9Z9Z9", now);

        DialogTurnResult result = processor.processTurn(session, "Yes, that's correct.", now);

        verify(customerLookupPort).lookupCustomerByPnr(new Pnr("Z9Z9Z9"));
        verify(disruptionTicketPort, never()).finalizeTicket(any());
        verify(ticketIntentPublisherPort, never()).publishTicketFilled(any());

        assertThat(result.targetState())
                .isInstanceOf(CallSessionState.CollectingPassengerDetails.class);
        assertThat(session.state()).isInstanceOf(CallSessionState.CollectingPassengerDetails.class);
        assertThat(result.responseText()).contains("Zulu-9-Zulu-9-Zulu-9");
        assertThat(TurnBrevityValidator.isValid(result.responseText())).isTrue();
    }

    @Test
    @DisplayName("Handles negative acoustic confirmation and reprompts for PNR without CRM lookup")
    void negativeConfirmation_revertsToCollectingPassengerDetails() {
        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);

        processor.processTurn(session, "A8F9X2", now);
        assertThat(session.state())
                .isInstanceOf(CallSessionState.AwaitingBookingConfirmation.class);

        DialogTurnResult result = processor.processTurn(session, "No, that is incorrect.", now);

        verify(customerLookupPort, never()).lookupCustomerByPnr(any());
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
