package com.voicepulse.conversation.infrastructure.fallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.instancio.Select.field;

import com.voicepulse.conversation.domain.event.CallStateTransitioned;
import com.voicepulse.conversation.domain.event.TurnCompleted;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.DialogTurnResult;
import com.voicepulse.conversation.domain.model.OperatorHandoffReceipt;
import com.voicepulse.conversation.domain.model.OperatorQueueStatus;
import com.voicepulse.conversation.domain.model.TicketSubmissionReceipt;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Null Object Fallback Ports Tests")
class FallbackPortsTest {

    @Test
    @DisplayName("NoOpProgressConversationUseCase operates as a safe Null Object")
    void progressConversationUseCaseFallback() {
        NoOpProgressConversationUseCase useCase = new NoOpProgressConversationUseCase();
        CallId callId = CallId.generate();
        Instant now = Instant.now();

        ConversationSession session1 = useCase.startConversation(callId, now);
        assertThat(session1.callId()).isEqualTo(callId);

        ConversationSession session2 = useCase.progressTurn(callId, "hello", now);
        assertThat(session2.callId()).isEqualTo(callId);

        Optional<ConversationSession> sessionOpt = useCase.findSession(callId);
        assertThat(sessionOpt).isEmpty();
    }

    @Test
    @DisplayName("NoOpProcessCallControlUseCase operates as a safe Null Object")
    void processCallControlUseCaseFallback() {
        NoOpProcessCallControlUseCase useCase = new NoOpProcessCallControlUseCase();
        CallId callId = CallId.generate();
        Instant now = Instant.now();

        assertThatCode(
                        () -> {
                            useCase.handleCallStarted(callId, now);
                            useCase.handleBargeIn(callId, now);
                            useCase.handleHangup(callId);
                        })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NoOpConversationStatePublisherPort operates as a safe Null Object")
    void conversationStatePublisherPortFallback() {
        NoOpConversationStatePublisherPort port = new NoOpConversationStatePublisherPort();
        CallId callId = CallId.generate();
        Instant now = Instant.now();
        CallSessionState state = new CallSessionState.Connected(now);

        assertThatCode(
                        () -> {
                            port.publishStateTransition(
                                    new CallStateTransitioned(
                                            callId,
                                            state,
                                            new CallSessionState.Greeting(now),
                                            now));
                            port.publishTurnCompleted(new TurnCompleted(callId, 1, state, now));
                        })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NoOpCallControlDispatcherPort operates as a safe Null Object")
    void callControlDispatcherPortFallback() {
        NoOpCallControlDispatcherPort port = new NoOpCallControlDispatcherPort();
        CallId callId = CallId.generate();
        Instant now = Instant.now();

        assertThatCode(() -> port.dispatch(new BargeInInterrupt(callId, 1L, now)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NoOpCustomerLookupPort returns empty Optional safely")
    void customerLookupPortFallback() {
        NoOpCustomerLookupPort port = new NoOpCustomerLookupPort();
        assertThat(port.lookupCustomerByPnr(new Pnr("A8F9X2"))).isEmpty();
    }

    @Test
    @DisplayName("NoOpDisruptionTicketPort returns deterministic fallback receipt")
    void disruptionTicketPortFallback() {
        NoOpDisruptionTicketPort port = new NoOpDisruptionTicketPort();
        AirlineDisruptionTicket ticket =
                Instancio.of(AirlineDisruptionTicket.class)
                        .set(field(AirlineDisruptionTicket::pnr), "A8F9X2")
                        .set(field(AirlineDisruptionTicket::payload), Map.of())
                        .create();

        TicketSubmissionReceipt receipt = port.finalizeTicket(ticket);
        assertThat(receipt.ticketId()).isEqualTo(ticket.ticketId().toString());
        assertThat(receipt.pnr()).isEqualTo(new Pnr("A8F9X2"));
        assertThat(receipt.status()).isEqualTo("NO_OP_FALLBACK");
    }

    @Test
    @DisplayName("NoOpOperatorRoutingPort returns deterministic fallback queue and handoff status")
    void operatorRoutingPortFallback() {
        NoOpOperatorRoutingPort port = new NoOpOperatorRoutingPort();
        CallId callId = CallId.generate();
        UUID ticketId = UUID.randomUUID();

        OperatorQueueStatus queueStatus = port.checkQueueStatus("DISRUPTION_DESK_GOLD");
        assertThat(queueStatus.department()).isEqualTo("DISRUPTION_DESK_GOLD");
        assertThat(queueStatus.availableOperators()).isZero();

        OperatorHandoffReceipt receipt =
                port.transferToHumanOperator(callId, "DISRUPTION_DESK_GOLD", ticketId);
        assertThat(receipt.callId()).isEqualTo(callId);
        assertThat(receipt.ticketId()).isEqualTo(ticketId);
        assertThat(receipt.department()).isEqualTo("DISRUPTION_DESK_GOLD");
        assertThat(receipt.status()).isEqualTo("NO_OP_FALLBACK");
    }

    @Test
    @DisplayName("NoOpTicketIntentPublisherPort operates as a safe Null Object")
    void ticketIntentPublisherPortFallback() {
        NoOpTicketIntentPublisherPort port = new NoOpTicketIntentPublisherPort();
        TicketFormFilledEvent event =
                Instancio.of(TicketFormFilledEvent.class)
                        .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                        .set(
                                field(TicketFormFilledEvent::originalFlightNumber),
                                new FlightNumber("SW-104"))
                        .set(field(TicketFormFilledEvent::payload), Map.of())
                        .create();

        assertThatCode(() -> port.publishTicketFilled(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NoOpConversationDialogProcessorPort operates as a safe Null Object")
    void conversationDialogProcessorPortFallback() {
        NoOpConversationDialogProcessorPort port = new NoOpConversationDialogProcessorPort();
        CallId callId = CallId.generate();
        Instant now = Instant.now();
        ConversationSession session = ConversationSession.start(callId, now);

        DialogTurnResult result = port.processTurn(session, "Hello", now);
        assertThat(result.responseText()).isNotBlank();
        assertThat(result.targetState()).isEqualTo(session.state());
        assertThat(result.disruptionTicket()).isEmpty();
    }
}
