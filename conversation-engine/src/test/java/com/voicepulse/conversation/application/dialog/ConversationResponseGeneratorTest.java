package com.voicepulse.conversation.application.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.conversation.domain.linguistics.TurnBrevityValidator;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.RebookingPreference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("ConversationResponseGenerator Unit Tests")
class ConversationResponseGeneratorTest {

    private ConversationResponseGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ConversationResponseGenerator();
    }

    @Test
    @DisplayName("Greeting adheres to 25-word brevity rule")
    void greeting_isWithinBrevityLimit() {
        String greeting = generator.greeting();
        assertThat(TurnBrevityValidator.isValid(greeting)).isTrue();
        assertThat(greeting).contains("Welcome to the Airline Disruption Hotline");
    }

    @Test
    @DisplayName(
            "Acoustic confirmation prompt formats NATO phonetic tokens and adheres to brevity rule")
    void acousticConfirmation_formatsPnrCorrectly() {
        String prompt = generator.acousticConfirmation("A8F9X2");
        assertThat(TurnBrevityValidator.isValid(prompt)).isTrue();
        assertThat(prompt).contains("Alpha-8-Foxtrot-9-Xray-2");
        assertThat(prompt).endsWith("Is that correct?");
    }

    @Test
    @DisplayName("PNR reprompt variations adhere to 25-word brevity rule")
    void pnrReprompt_isWithinBrevityLimit() {
        String firstReprompt = generator.pnrReprompt(1);
        String secondReprompt = generator.pnrReprompt(2);

        assertThat(TurnBrevityValidator.isValid(firstReprompt)).isTrue();
        assertThat(TurnBrevityValidator.isValid(secondReprompt)).isTrue();
        assertThat(firstReprompt).contains("booking reference");
        assertThat(secondReprompt).contains("NATO phonetic");
    }

    @Test
    @DisplayName("Negative confirmation reprompt adheres to 25-word brevity rule")
    void negativeConfirmationReprompt_isWithinBrevityLimit() {
        String reprompt = generator.negativeConfirmationReprompt();
        assertThat(TurnBrevityValidator.isValid(reprompt)).isTrue();
        assertThat(reprompt).contains("booking reference");
    }

    @ParameterizedTest
    @EnumSource(DisruptionReason.class)
    @DisplayName("Disruption inquiry prompt formats flight number and reason within brevity limit")
    void disruptionInquiry_isWithinBrevityLimit(DisruptionReason reason) {
        String prompt = generator.disruptionInquiry("SW-104", reason);
        assertThat(TurnBrevityValidator.isValid(prompt)).isTrue();
        assertThat(prompt).contains("SW-104");
    }

    @ParameterizedTest
    @EnumSource(RebookingPreference.class)
    @DisplayName("Handoff ready prompt formats preferences accurately within brevity limit")
    void handoffReady_isWithinBrevityLimit(RebookingPreference preference) {
        String hotelPrompt = generator.handoffReady("SW-104", true, preference);
        String flightPrompt = generator.handoffReady("SW-104", false, preference);

        assertThat(TurnBrevityValidator.isValid(hotelPrompt)).isTrue();
        assertThat(TurnBrevityValidator.isValid(flightPrompt)).isTrue();
        assertThat(hotelPrompt).contains("hotel request");
        assertThat(hotelPrompt).contains("priority disruption team");
    }

    @Test
    @DisplayName("Transferred and fallback error prompts adhere to 25-word brevity rule")
    void transferredAndFallback_areWithinBrevityLimit() {
        String transferred = generator.transferred();
        String fallback = generator.fallbackError();

        assertThat(TurnBrevityValidator.isValid(transferred)).isTrue();
        assertThat(TurnBrevityValidator.isValid(fallback)).isTrue();
    }

    @Test
    @DisplayName("Acoustic confirmation rejects invalid PNR strings")
    void acousticConfirmation_invalidPnr_throwsException() {
        assertThatThrownBy(() -> generator.acousticConfirmation(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> generator.acousticConfirmation("SHORT"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
