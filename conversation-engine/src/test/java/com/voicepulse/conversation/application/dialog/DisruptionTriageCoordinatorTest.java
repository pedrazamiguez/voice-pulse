package com.voicepulse.conversation.application.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voicepulse.conversation.domain.exception.IllegalConversationStateException;
import com.voicepulse.conversation.domain.model.ActiveItinerary;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.CustomerProfile;
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
@DisplayName("DisruptionTriageCoordinator Unit Tests")
class DisruptionTriageCoordinatorTest {

    @Mock private CustomerLookupPort customerLookupPort;
    @Mock private DisruptionTicketPort disruptionTicketPort;
    @Mock private OperatorRoutingPort operatorRoutingPort;
    @Mock private TicketIntentPublisherPort ticketIntentPublisherPort;

    @Captor private ArgumentCaptor<TicketFormFilledEvent> eventCaptor;

    private DisruptionTriageCoordinator coordinator;
    private CallId callId;
    private Instant now;

    @BeforeEach
    void setUp() {
        coordinator =
                new DisruptionTriageCoordinator(
                        customerLookupPort,
                        disruptionTicketPort,
                        operatorRoutingPort,
                        ticketIntentPublisherPort,
                        new SkillGroupRoutingPolicy());
        callId = CallId.generate();
        now = Instant.now();
    }

    @Test
    @DisplayName("lookupAndConfirmBooking transitions to DisruptionTriage when profile found")
    void lookupAndConfirmBooking_whenFound_transitionsToDisruptionTriage() {
        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);
        session.promptPassengerDetails(1);
        session.awaitBookingConfirmation("A8F9X2", now);

        CustomerProfile profile =
                Instancio.of(CustomerProfile.class)
                        .set(field(CustomerProfile::pnr), new Pnr("A8F9X2"))
                        .set(field(CustomerProfile::customerName), "Elena")
                        .set(field(CustomerProfile::frequentFlyerTier), FrequentFlyerTier.PLATINUM)
                        .set(
                                field(CustomerProfile::activeItinerary),
                                Instancio.of(ActiveItinerary.class)
                                        .set(
                                                field(ActiveItinerary::flightNumber),
                                                new FlightNumber("SW-202"))
                                        .set(
                                                field(ActiveItinerary::disruptionReason),
                                                DisruptionReason.DELAYED_MAINTENANCE)
                                        .create())
                        .create();

        when(customerLookupPort.lookupCustomerByPnr(new Pnr("A8F9X2")))
                .thenReturn(Optional.of(profile));

        Optional<CustomerProfile> resolved =
                coordinator.lookupAndConfirmBooking(session, new Pnr("A8F9X2"), now);

        assertThat(resolved).contains(profile);
        assertThat(session.customerProfile()).contains(profile);
        assertThat(session.state())
                .isInstanceOf(CallSessionState.DisruptionTriage.class)
                .satisfies(
                        state -> {
                            CallSessionState.DisruptionTriage triage =
                                    (CallSessionState.DisruptionTriage) state;
                            assertThat(triage.customerName()).isEqualTo("Elena");
                            assertThat(triage.flightNumber()).isEqualTo("SW-202");
                        });
    }

    @Test
    @DisplayName("lookupAndConfirmBooking reverts to CollectingPassengerDetails when PNR not found")
    void lookupAndConfirmBooking_whenMissing_revertsToCollectingPassengerDetails() {
        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);
        session.promptPassengerDetails(1);
        session.awaitBookingConfirmation("X1Y2Z3", now);

        when(customerLookupPort.lookupCustomerByPnr(new Pnr("X1Y2Z3")))
                .thenReturn(Optional.empty());

        Optional<CustomerProfile> resolved =
                coordinator.lookupAndConfirmBooking(session, new Pnr("X1Y2Z3"), now);

        assertThat(resolved).isEmpty();
        assertThat(session.state()).isInstanceOf(CallSessionState.CollectingPassengerDetails.class);
        verifyNoInteractions(disruptionTicketPort, operatorRoutingPort, ticketIntentPublisherPort);
    }

    @Test
    @DisplayName(
            "finalizeAndRouteTriage orchestrates ticket submission, routing policy, transfer, and"
                    + " Kafka event")
    void finalizeAndRouteTriage_executesAllOutboundPortsAndPreparesHandoff() {
        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);
        session.promptPassengerDetails(1);
        session.awaitBookingConfirmation("A8F9X2", now);

        CustomerProfile standardProfile =
                Instancio.of(CustomerProfile.class)
                        .set(field(CustomerProfile::pnr), new Pnr("A8F9X2"))
                        .set(field(CustomerProfile::customerName), "Carlos")
                        .set(field(CustomerProfile::frequentFlyerTier), FrequentFlyerTier.STANDARD)
                        .set(
                                field(CustomerProfile::activeItinerary),
                                Instancio.of(ActiveItinerary.class)
                                        .set(
                                                field(ActiveItinerary::flightNumber),
                                                new FlightNumber("SW-308"))
                                        .set(
                                                field(ActiveItinerary::disruptionReason),
                                                DisruptionReason.DELAYED_MAINTENANCE)
                                        .create())
                        .create();
        session.confirmBooking(standardProfile, now);

        when(disruptionTicketPort.finalizeTicket(any(AirlineDisruptionTicket.class)))
                .thenReturn(
                        Instancio.of(TicketSubmissionReceipt.class)
                                .set(field(TicketSubmissionReceipt::pnr), new Pnr("A8F9X2"))
                                .create());
        when(operatorRoutingPort.transferToHumanOperator(
                        eq(callId), eq("DISRUPTION_DESK_ACCESSIBILITY"), any(UUID.class)))
                .thenReturn(
                        Instancio.of(OperatorHandoffReceipt.class)
                                .set(field(OperatorHandoffReceipt::callId), callId)
                                .set(
                                        field(OperatorHandoffReceipt::department),
                                        "DISRUPTION_DESK_ACCESSIBILITY")
                                .create());

        AirlineDisruptionTicket ticket =
                coordinator.finalizeAndRouteTriage(
                        session, RebookingPreference.SAME_DAY_LATER, false, true, now);

        assertThat(ticket.disruptionReason()).isEqualTo(DisruptionReason.DELAYED_MAINTENANCE);
        assertThat(ticket.specialAssistance()).isTrue();
        assertThat(session.state())
                .isEqualTo(
                        new CallSessionState.HandoffReady(
                                ticket.ticketId().toString(), "DISRUPTION_DESK_ACCESSIBILITY"));

        verify(disruptionTicketPort).finalizeTicket(ticket);
        verify(operatorRoutingPort)
                .transferToHumanOperator(
                        callId, "DISRUPTION_DESK_ACCESSIBILITY", ticket.ticketId());
        verify(ticketIntentPublisherPort).publishTicketFilled(eventCaptor.capture());

        TicketFormFilledEvent event = eventCaptor.getValue();
        assertThat(event.callId()).isEqualTo(callId);
        assertThat(event.ticketId()).isEqualTo(ticket.ticketId());
        assertThat(event.pnr()).isEqualTo(new Pnr("A8F9X2"));
        assertThat(event.originalFlightNumber()).isEqualTo(new FlightNumber("SW-308"));
        assertThat(event.targetSkillGroup()).isEqualTo("DISRUPTION_DESK_ACCESSIBILITY");
    }

    @Test
    @DisplayName("finalizeAndRouteTriage rejects session not in DisruptionTriage state")
    void finalizeAndRouteTriage_invalidState_throwsIllegalConversationStateException() {
        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);

        assertThatThrownBy(
                        () ->
                                coordinator.finalizeAndRouteTriage(
                                        session,
                                        RebookingPreference.NEXT_AVAILABLE,
                                        false,
                                        false,
                                        now))
                .isInstanceOf(IllegalConversationStateException.class);
    }
}
