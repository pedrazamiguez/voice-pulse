package com.voicepulse.conversation.application.dialog;

import com.voicepulse.conversation.domain.linguistics.AcousticPnrVerifier;
import com.voicepulse.conversation.domain.linguistics.TurnBrevityValidator;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.RebookingPreference;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Dialog response generator producing context-aware conversational turns for the SkyWay Airlines
 * Disruption Hotline.
 *
 * <p>Enforces the 25-word maximum brevity ceiling (RFC-0003 and AGENTS.md §4) across every
 * generated prompt to preserve real-time telephony pacing and low TTFA latency.
 */
@Component
public class ConversationResponseGenerator {

    private static final String GREETING_PROMPT =
            "Welcome to the Airline Disruption Hotline. To quickly assist you, please state your"
                    + " six-character booking reference or PNR.";

    private static final String PNR_REPROMPT_FIRST =
            "I could not understand your booking reference. Please state your six-character PNR"
                    + " code clearly.";

    private static final String PNR_REPROMPT_SUBSEQUENT =
            "I am still having trouble reading your code. Please pronounce each letter using NATO"
                    + " phonetic words.";

    private static final String NEGATIVE_CONFIRMATION_REPROMPT =
            "I apologize for the misunderstanding. Please state your six-character booking"
                    + " reference again.";

    private static final String TRANSFERRED_PROMPT =
            "Connecting you to an agent now. Please stay on the line.";

    private static final String FALLBACK_ERROR_PROMPT =
            "I apologize, but I am unable to process that. Connecting you to an agent now.";

    /** Generates the initial greeting prompt (19 words). */
    public String greeting() {
        return validated(GREETING_PROMPT);
    }

    /**
     * Generates an acoustic phonetic confirmation prompt for the given 6-character PNR (8 words).
     *
     * @param pnr verified 6-character PNR string
     * @return confirmation prompt with NATO phonetic spelling
     */
    public String acousticConfirmation(String pnr) {
        Objects.requireNonNull(pnr, "pnr cannot be null");
        return validated(AcousticPnrVerifier.generateConfirmationPrompt(pnr));
    }

    /**
     * Generates a reprompt when PNR transcription fails (14-16 words).
     *
     * @param attemptCount current collection attempt number
     * @return contextual reprompt
     */
    public String pnrReprompt(int attemptCount) {
        return attemptCount <= 1
                ? validated(PNR_REPROMPT_FIRST)
                : validated(PNR_REPROMPT_SUBSEQUENT);
    }

    /** Generates a reprompt when the caller denies the acoustic confirmation (13 words). */
    public String negativeConfirmationReprompt() {
        return validated(NEGATIVE_CONFIRMATION_REPROMPT);
    }

    /**
     * Generates a disruption inquiry prompt presenting remedy options (17-20 words).
     *
     * @param flightNumber disrupted flight number
     * @param reason disruption root cause
     * @return contextual inquiry prompt
     */
    public String disruptionInquiry(String flightNumber, DisruptionReason reason) {
        Objects.requireNonNull(flightNumber, "flightNumber cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");

        String prompt =
                switch (reason) {
                    case CANCELED_WEATHER ->
                            "I see your flight "
                                    + flightNumber
                                    + " was cancelled due to weather. Would you like the next"
                                    + " available flight or a hotel accommodation?";
                    case DELAYED_MAINTENANCE ->
                            "Your flight "
                                    + flightNumber
                                    + " is delayed for maintenance. Would you like to rebook on"
                                    + " the next flight or request assistance?";
                    case CANCELED_CREW_REST ->
                            "Flight "
                                    + flightNumber
                                    + " was cancelled due to crew rest requirements. Would you"
                                    + " like the next available flight or a refund?";
                    case DIVERTED_TECHNICAL ->
                            "Flight "
                                    + flightNumber
                                    + " has been diverted. Would you like hotel accommodations"
                                    + " or rebooking on the next available flight?";
                };

        return validated(prompt);
    }

    /**
     * Generates a handoff ready confirmation prompt upon ticket compilation (17 words).
     *
     * @param flightNumber disrupted flight number
     * @param hotelRequired true if hotel accommodation was requested
     * @param preference passenger rebooking preference
     * @return handoff preparation prompt
     */
    public String handoffReady(
            String flightNumber, boolean hotelRequired, RebookingPreference preference) {
        Objects.requireNonNull(flightNumber, "flightNumber cannot be null");
        Objects.requireNonNull(preference, "preference cannot be null");

        String requestType;
        if (hotelRequired) {
            requestType = "hotel request";
        } else if (preference == RebookingPreference.REFUND_REQUESTED) {
            requestType = "refund request";
        } else {
            requestType = "rebooking request";
        }

        String prompt =
                "I have prepared your "
                        + requestType
                        + " for flight "
                        + flightNumber
                        + ". Transferring you to our priority disruption team now.";

        return validated(prompt);
    }

    /** Generates the transfer prompt (11 words). */
    public String transferred() {
        return validated(TRANSFERRED_PROMPT);
    }

    /** Generates a safe fallback prompt when parsing encounters unrecoverable errors (15 words). */
    public String fallbackError() {
        return validated(FALLBACK_ERROR_PROMPT);
    }

    private static String validated(String prompt) {
        TurnBrevityValidator.validate(prompt);
        return prompt;
    }
}
