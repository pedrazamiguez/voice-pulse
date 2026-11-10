package com.voicepulse.conversation.domain.linguistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.conversation.domain.exception.TurnBrevityExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("TurnBrevityValidator Domain Guardrail Tests")
class TurnBrevityValidatorTest {

    @Nested
    @DisplayName("Word Counting Logic")
    class WordCountingTests {

        @Test
        void nullEmptyOrBlankReturnsZero() {
            assertThat(TurnBrevityValidator.countWords(null)).isZero();
            assertThat(TurnBrevityValidator.countWords("")).isZero();
            assertThat(TurnBrevityValidator.countWords("   \t  \n  ")).isZero();
        }

        @Test
        void countsSingleWord() {
            assertThat(TurnBrevityValidator.countWords("Hello")).isEqualTo(1);
            assertThat(TurnBrevityValidator.countWords("   Hello   ")).isEqualTo(1);
        }

        @Test
        void handlesIrregularWhitespaceSeparators() {
            String multiWhitespace = "One \t  two   \n\r  three    four";
            assertThat(TurnBrevityValidator.countWords(multiWhitespace)).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("25-Word Brevity Boundary Enforcement")
    class BoundaryTests {

        @Test
        void exactlyTwentyFiveWordsPassesValidation() {
            String twentyFiveWords =
                    "One two three four five six seven eight nine ten eleven twelve thirteen"
                        + " fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one"
                        + " twenty-two twenty-three twenty-four twenty-five.";

            assertThat(TurnBrevityValidator.countWords(twentyFiveWords)).isEqualTo(25);
            assertThat(TurnBrevityValidator.isValid(twentyFiveWords)).isTrue();
            TurnBrevityValidator.validate(twentyFiveWords);
        }

        @Test
        void twentySixWordsFailsValidationAndThrowsException() {
            String twentySixWords =
                    "One two three four five six seven eight nine ten eleven twelve thirteen"
                        + " fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one"
                        + " twenty-two twenty-three twenty-four twenty-five twenty-six.";

            assertThat(TurnBrevityValidator.countWords(twentySixWords)).isEqualTo(26);
            assertThat(TurnBrevityValidator.isValid(twentySixWords)).isFalse();

            assertThatThrownBy(() -> TurnBrevityValidator.validate(twentySixWords))
                    .isInstanceOf(TurnBrevityExceededException.class)
                    .satisfies(
                            ex -> {
                                TurnBrevityExceededException brevityEx =
                                        (TurnBrevityExceededException) ex;
                                assertThat(brevityEx.wordCount()).isEqualTo(26);
                                assertThat(brevityEx.maxWords()).isEqualTo(25);
                                assertThat(brevityEx.prompt()).isEqualTo(twentySixWords);
                                assertThat(brevityEx.getMessage())
                                        .contains(
                                                "Turn prompt exceeds maximum 25-word limit: actual"
                                                        + " 26 words > limit 25");
                            });
        }

        @Test
        void validateWithDetailsReturnsCompleteRecord() {
            String validPrompt = "Please state your booking reference.";
            BrevityValidationResult result = TurnBrevityValidator.validateWithDetails(validPrompt);

            assertThat(result.isValid()).isTrue();
            assertThat(result.wordCount()).isEqualTo(5);
            assertThat(result.maxWords()).isEqualTo(25);
            assertThat(result.prompt()).isEqualTo(validPrompt);

            String overPrompt =
                    "One two three four five six seven eight nine ten eleven twelve thirteen"
                        + " fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one"
                        + " twenty-two twenty-three twenty-four twenty-five twenty-six.";
            BrevityValidationResult overResult =
                    TurnBrevityValidator.validateWithDetails(overPrompt);

            assertThat(overResult.isValid()).isFalse();
            assertThat(overResult.wordCount()).isEqualTo(26);
            assertThat(overResult.maxWords()).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("Turn Prompt Truncation")
    class TruncationTests {

        @Test
        void promptWithinCeilingIsNotModified() {
            String prompt = "Welcome to the Airline Disruption Hotline.";
            assertThat(TurnBrevityValidator.truncateToLimit(prompt)).isEqualTo(prompt);
        }

        @Test
        void nullOrBlankReturnsEmptyOrOriginal() {
            assertThat(TurnBrevityValidator.truncateToLimit(null)).isEmpty();
            assertThat(TurnBrevityValidator.truncateToLimit("   ")).isEmpty();
        }

        @Test
        void promptExceedingCeilingIsTruncatedToTwentyFiveWords() {
            String longPrompt =
                    "One two three four five six seven eight nine ten eleven twelve thirteen"
                        + " fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one"
                        + " twenty-two twenty-three twenty-four twenty-five twenty-six twenty-seven"
                        + " twenty-eight.";

            String truncated = TurnBrevityValidator.truncateToLimit(longPrompt);

            assertThat(TurnBrevityValidator.countWords(truncated)).isEqualTo(25);
            assertThat(TurnBrevityValidator.isValid(truncated)).isTrue();
            assertThat(truncated)
                    .isEqualTo(
                            "One two three four five six seven eight nine ten eleven twelve"
                                + " thirteen fourteen fifteen sixteen seventeen eighteen nineteen"
                                + " twenty twenty-one twenty-two twenty-three twenty-four"
                                + " twenty-five");
        }
    }

    @Nested
    @DisplayName("Hotline Domain Standard Prompts Verification")
    class HotlinePromptsAuditTests {

        @ParameterizedTest(name = "Hotline turn prompt adheres to 25-word limit: ''{0}''")
        @ValueSource(
                strings = {
                    "Welcome to the Airline Disruption Hotline. To quickly assist you, please state"
                            + " your six-character booking reference or PNR.",
                    "I heard Alpha-8-Foxtrot-9-Xray-2. Is that correct?",
                    "I have prepared your hotel request for Chengdu. I am transferring you to our"
                            + " priority disruption team now.",
                    "Thank you for confirming your PNR. Please hold while I retrieve your"
                            + " disruption itinerary options."
                })
        void standardHotlinePromptsAdhereToBrevityRule(String prompt) {
            assertThat(TurnBrevityValidator.isValid(prompt))
                    .as(
                            "Prompt should be <= 25 words: '%s' (actual: %d)",
                            prompt, TurnBrevityValidator.countWords(prompt))
                    .isTrue();
            TurnBrevityValidator.validate(prompt);
        }
    }
}
