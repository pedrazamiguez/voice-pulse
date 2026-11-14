package com.voicepulse.conversation.application.dialog;

import com.voicepulse.conversation.domain.exception.IllegalConversationStateException;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.conversation.domain.model.DisruptionTicketAssembler;
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
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Shared application collaborator orchestrating outbound MCP tool ports and Kafka ticket intent
 * publishing across both {@code mock} and {@code live} conversational dialog strategies.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DisruptionTriageCoordinator {

    private static final DisruptionReason FALLBACK_DISRUPTION_REASON =
            DisruptionReason.CANCELED_WEATHER;
    private static final FrequentFlyerTier FALLBACK_LOYALTY_TIER = FrequentFlyerTier.STANDARD;

    private final @NonNull CustomerLookupPort customerLookupPort;
    private final @NonNull DisruptionTicketPort disruptionTicketPort;
    private final @NonNull OperatorRoutingPort operatorRoutingPort;
    private final @NonNull TicketIntentPublisherPort ticketIntentPublisherPort;
    private final @NonNull SkillGroupRoutingPolicy skillGroupRoutingPolicy;

    /**
     * Looks up a verified PNR via {@link CustomerLookupPort} and transitions the session either to
     * {@link CallSessionState.DisruptionTriage} when found, or back to {@link
     * CallSessionState.CollectingPassengerDetails} when not found.
     *
     * @param session active conversation session in {@link
     *     CallSessionState.AwaitingBookingConfirmation}
     * @param pnr acoustically verified 6-character PNR
     * @param timestamp turn timestamp
     * @return resolved {@link CustomerProfile} when present, or {@link Optional#empty()}
     */
    public Optional<CustomerProfile> lookupAndConfirmBooking(
            ConversationSession session, Pnr pnr, Instant timestamp) {
        Objects.requireNonNull(session, "session cannot be null");
        Objects.requireNonNull(pnr, "pnr cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        Optional<CustomerProfile> profileOpt = customerLookupPort.lookupCustomerByPnr(pnr);
        if (profileOpt.isPresent()) {
            CustomerProfile profile = profileOpt.get();
            session.confirmBooking(profile, timestamp);
            log.info(
                    "CRM booking confirmed: callId={}, pnr={}, flight={}, tier={}",
                    session.callId(),
                    pnr,
                    profile.activeItinerary().flightNumber(),
                    profile.frequentFlyerTier());
            return Optional.of(profile);
        }

        session.promptPassengerDetails(1);
        log.info(
                "CRM PNR not found, returning session to CollectingPassengerDetails: callId={},"
                        + " pnr={}",
                session.callId(),
                pnr);
        return Optional.empty();
    }

    /**
     * Compiles an {@link AirlineDisruptionTicket}, submits it via {@link DisruptionTicketPort},
     * resolves the target operator skill group, dispatches transfer via {@link
     * OperatorRoutingPort}, publishes {@link TicketFormFilledEvent} via {@link
     * TicketIntentPublisherPort}, and transitions the session to {@link
     * CallSessionState.HandoffReady}.
     *
     * @param session active conversation session in {@link CallSessionState.DisruptionTriage}
     * @param preference passenger rebooking preference
     * @param hotelRequired true if overnight hotel accommodation is required
     * @param specialAssistance true if wheelchair/accessibility assistance is required
     * @param timestamp turn timestamp
     * @return compiled and finalized disruption ticket
     */
    public AirlineDisruptionTicket finalizeAndRouteTriage(
            ConversationSession session,
            RebookingPreference preference,
            boolean hotelRequired,
            boolean specialAssistance,
            Instant timestamp) {
        return finalizeAndRouteTriage(
                session, preference, hotelRequired, specialAssistance, null, timestamp);
    }

    /**
     * Compiles, finalizes, routes, and publishes a disruption ticket, honoring an optional
     * department override when provided by a tool call.
     *
     * @param session active conversation session in {@link CallSessionState.DisruptionTriage}
     * @param preference passenger rebooking preference
     * @param hotelRequired true if overnight hotel accommodation is required
     * @param specialAssistance true if wheelchair/accessibility assistance is required
     * @param departmentOverride optional skill-group override; policy resolves if null or blank
     * @param timestamp turn timestamp
     * @return compiled and finalized disruption ticket
     */
    public AirlineDisruptionTicket finalizeAndRouteTriage(
            ConversationSession session,
            RebookingPreference preference,
            boolean hotelRequired,
            boolean specialAssistance,
            String departmentOverride,
            Instant timestamp) {
        Objects.requireNonNull(session, "session cannot be null");
        Objects.requireNonNull(preference, "preference cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        if (!(session.state() instanceof CallSessionState.DisruptionTriage triage)) {
            throw new IllegalConversationStateException(
                    "Cannot finalize disruption triage from state: " + session.state());
        }

        DisruptionReason disruptionReason =
                session.customerProfile()
                        .map(profile -> profile.activeItinerary().disruptionReason())
                        .orElse(FALLBACK_DISRUPTION_REASON);
        FrequentFlyerTier loyaltyTier =
                session.customerProfile()
                        .map(CustomerProfile::frequentFlyerTier)
                        .orElse(FALLBACK_LOYALTY_TIER);

        AirlineDisruptionTicket ticket =
                DisruptionTicketAssembler.assemble(
                        session.callId().value(),
                        triage.customerName(),
                        triage.pnr(),
                        triage.flightNumber(),
                        disruptionReason,
                        preference,
                        hotelRequired,
                        specialAssistance,
                        timestamp);

        TicketSubmissionReceipt submissionReceipt = disruptionTicketPort.finalizeTicket(ticket);
        String targetSkillGroup =
                (departmentOverride != null && !departmentOverride.isBlank())
                        ? departmentOverride.trim()
                        : skillGroupRoutingPolicy.resolveSkillGroup(
                                loyaltyTier, specialAssistance, disruptionReason);

        OperatorHandoffReceipt handoffReceipt =
                operatorRoutingPort.transferToHumanOperator(
                        session.callId(), targetSkillGroup, ticket.ticketId());

        TicketFormFilledEvent event =
                new TicketFormFilledEvent(
                        session.callId(),
                        ticket.ticketId(),
                        ticket.passengerName(),
                        new Pnr(ticket.pnr()),
                        new FlightNumber(ticket.originalFlightNumber()),
                        ticket.disruptionReason(),
                        ticket.rebookingPreference(),
                        ticket.hotelRequired(),
                        ticket.specialAssistance(),
                        targetSkillGroup,
                        ticket.payload(),
                        timestamp);
        ticketIntentPublisherPort.publishTicketFilled(event);

        session.attachDisruptionTicket(ticket);
        session.prepareHandoff(ticket.ticketId().toString(), targetSkillGroup);

        log.info(
                "Disruption triage finalized and routed: callId={}, ticketId={}, receiptStatus={},"
                        + " skillGroup={}, routingStatus={}",
                session.callId(),
                ticket.ticketId(),
                submissionReceipt.status(),
                targetSkillGroup,
                handoffReceipt.status());

        return ticket;
    }
}
