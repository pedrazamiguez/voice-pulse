package com.voicepulse.conversation.application.dialog;

import com.voicepulse.conversation.domain.linguistics.AcousticPnrVerifier;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.DisruptionTicketAssembler;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deterministic mock dialog simulator executing the SkyWay Airlines Disruption Hotline
 * conversational flow (RFC-0001 §4.3 and RFC-0003 §4).
 *
 * <p>Parses caller utterances via regex and domain linguistics heuristics, enforces the 25-word
 * brevity ceiling, compiles structured disruption tickets, and executes with sub-50ms deterministic
 * latency.
 */
@Component
@RequiredArgsConstructor
public class MockLlmConversationProcessor {

    private static final String DEFAULT_PASSENGER_NAME = "Andres";
    private static final String DEFAULT_FLIGHT_NUMBER = "SW-104";
    private static final DisruptionReason DEFAULT_DISRUPTION_REASON =
            DisruptionReason.CANCELED_WEATHER;
    private static final String TARGET_SKILL_GROUP = "DISRUPTION_DESK_PRIORITY";

    private static final Pattern AFFIRMATIVE_PATTERN =
            Pattern.compile(
                    "(?i)\\b(yes|yeah|yep|correct|that('s| is)"
                            + " right|confirm|right|sure|affirmative|ok|okay)\\b");
    private static final Pattern NEGATIVE_PATTERN =
            Pattern.compile("(?i)\\b(no|nope|incorrect|wrong|that('s| is) wrong|negative)\\b");
    private static final Pattern REFUND_PATTERN =
            Pattern.compile("(?i)\\b(refund|money back|cancel)\\b");
    private static final Pattern HOTEL_PATTERN =
            Pattern.compile("(?i)\\b(hotel|room|accommodation|overnight|stay)\\b");
    private static final Pattern SAME_DAY_PATTERN =
            Pattern.compile("(?i)\\b(later today|same day|later flight)\\b");
    private static final Pattern SPECIAL_ASSISTANCE_PATTERN =
            Pattern.compile(
                    "(?i)\\b(wheelchair|assistance|special assistance|disabled|elderly)\\b");
    private static final Pattern EMBEDDED_6CHAR_PATTERN = Pattern.compile("\\b([A-Za-z0-9]{6})\\b");

    private final @NonNull ConversationResponseGenerator responseGenerator;

    /**
     * Processes a conversational turn for the given session and user utterance.
     *
     * @param session active conversation session aggregate root
     * @param utterance transcribed user speech input
     * @param timestamp timestamp of the turn
     * @return deterministic turn execution result
     */
    public DialogTurnResult processTurn(
            ConversationSession session, String utterance, Instant timestamp) {
        Objects.requireNonNull(session, "session cannot be null");
        Objects.requireNonNull(utterance, "utterance cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        long startNanos = System.nanoTime();
        String responseText;
        Optional<AirlineDisruptionTicket> ticketOpt = Optional.empty();

        CallSessionState currentState = session.state();

        switch (currentState) {
            case CallSessionState.Connected connected -> {
                session.greet(timestamp);
                responseText = responseGenerator.greeting();
            }
            case CallSessionState.Greeting greeting -> {
                Optional<Pnr> pnrOpt = extractPnr(utterance);
                if (pnrOpt.isPresent()) {
                    String pnrValue = pnrOpt.get().value();
                    session.promptPassengerDetails(1);
                    session.awaitBookingConfirmation(pnrValue, timestamp);
                    responseText = responseGenerator.acousticConfirmation(pnrValue);
                } else {
                    session.promptPassengerDetails(1);
                    responseText = responseGenerator.pnrReprompt(1);
                }
            }
            case CallSessionState.CollectingPassengerDetails collecting -> {
                Optional<Pnr> pnrOpt = extractPnr(utterance);
                if (pnrOpt.isPresent()) {
                    String pnrValue = pnrOpt.get().value();
                    session.awaitBookingConfirmation(pnrValue, timestamp);
                    responseText = responseGenerator.acousticConfirmation(pnrValue);
                } else {
                    int nextAttempt = collecting.attemptCount() + 1;
                    session.promptPassengerDetails(nextAttempt);
                    responseText = responseGenerator.pnrReprompt(nextAttempt);
                }
            }
            case CallSessionState.AwaitingBookingConfirmation awaiting -> {
                if (AFFIRMATIVE_PATTERN.matcher(utterance).find()) {
                    String pnr = awaiting.transcribedPnr();
                    session.confirmBooking(
                            pnr, DEFAULT_PASSENGER_NAME, DEFAULT_FLIGHT_NUMBER, timestamp);
                    responseText =
                            responseGenerator.disruptionInquiry(
                                    DEFAULT_FLIGHT_NUMBER, DEFAULT_DISRUPTION_REASON);
                } else if (NEGATIVE_PATTERN.matcher(utterance).find()) {
                    session.promptPassengerDetails(1);
                    responseText = responseGenerator.negativeConfirmationReprompt();
                } else {
                    responseText =
                            responseGenerator.acousticConfirmation(awaiting.transcribedPnr());
                }
            }
            case CallSessionState.DisruptionTriage triage -> {
                boolean hotelRequired = HOTEL_PATTERN.matcher(utterance).find();
                boolean specialAssistance = SPECIAL_ASSISTANCE_PATTERN.matcher(utterance).find();
                RebookingPreference preference = resolvePreference(utterance);

                AirlineDisruptionTicket ticket =
                        DisruptionTicketAssembler.assemble(
                                session.callId().value(),
                                triage.customerName(),
                                triage.pnr(),
                                triage.flightNumber(),
                                DEFAULT_DISRUPTION_REASON,
                                preference,
                                hotelRequired,
                                specialAssistance,
                                timestamp);

                session.attachDisruptionTicket(ticket);
                session.prepareHandoff(ticket.ticketId().toString(), TARGET_SKILL_GROUP);
                responseText =
                        responseGenerator.handoffReady(
                                triage.flightNumber(), hotelRequired, preference);
                ticketOpt = Optional.of(ticket);
            }
            case CallSessionState.HandoffReady handoff -> {
                responseText = responseGenerator.transferred();
                ticketOpt = session.disruptionTicket();
            }
            case CallSessionState.Transferred transferred -> {
                responseText = responseGenerator.transferred();
                ticketOpt = session.disruptionTicket();
            }
            case CallSessionState.Terminated terminated -> {
                responseText = responseGenerator.transferred();
                ticketOpt = session.disruptionTicket();
            }
        }

        long executionDurationNanos = System.nanoTime() - startNanos;
        return new DialogTurnResult(
                responseText, session.state(), ticketOpt, executionDurationNanos);
    }

    private Optional<Pnr> extractPnr(String utterance) {
        // Direct attempt via acoustic parser (NATO tokens, single digits, etc.)
        Optional<Pnr> acousticParsed = AcousticPnrVerifier.tryParse(utterance);
        if (acousticParsed.isPresent()) {
            return acousticParsed;
        }

        // Regex fallback for embedded 6-character alphanumeric tokens (e.g., "reference is A8F9X2")
        Matcher matcher = EMBEDDED_6CHAR_PATTERN.matcher(utterance);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            try {
                return Optional.of(AcousticPnrVerifier.validate(candidate));
            } catch (IllegalArgumentException ignored) {
                // Ignore and keep searching
            }
        }

        return Optional.empty();
    }

    private static RebookingPreference resolvePreference(String utterance) {
        if (REFUND_PATTERN.matcher(utterance).find()) {
            return RebookingPreference.REFUND_REQUESTED;
        }
        if (SAME_DAY_PATTERN.matcher(utterance).find()) {
            return RebookingPreference.SAME_DAY_LATER;
        }
        return RebookingPreference.NEXT_AVAILABLE;
    }
}
