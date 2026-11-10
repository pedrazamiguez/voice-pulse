package com.voicepulse.conversation.domain.linguistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.conversation.domain.exception.InvalidPnrTranscriptionException;
import com.voicepulse.shared.model.Pnr;
import java.util.Optional;
import org.instancio.Instancio;
import org.instancio.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AcousticPnrVerifier Domain Tests")
class AcousticPnrVerifierTest {

    @Nested
    @DisplayName("PNR Validation & Normalization")
    class PnrValidationTests {

        @Test
        void validSixCharacterPnrNormalizesToUppercase() {
            Pnr pnr = AcousticPnrVerifier.validate("a8f9x2");
            assertThat(pnr.value()).isEqualTo("A8F9X2");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "A", "AB12C", "AB12CDE", "AB-12C", "A8F9X!", "   "})
        void invalidPnrThrowsIllegalArgumentException(String invalidPnr) {
            assertThatThrownBy(() -> AcousticPnrVerifier.validate(invalidPnr))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nullPnrThrowsException() {
            assertThatThrownBy(() -> AcousticPnrVerifier.validate(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Acoustic Phonetic Formatting")
    class AcousticFormattingTests {

        @Test
        void formatsPnrWithNatoLettersAndDigitsPerRfc0003() {
            String acoustic = AcousticPnrVerifier.formatAcousticPnr("A8F9X2");
            assertThat(acoustic).isEqualTo("Alpha-8-Foxtrot-9-Xray-2");
        }

        @Test
        void formatsPnrFromPnrDomainObject() {
            Pnr pnr = new Pnr("AB7DF9");
            String acoustic = AcousticPnrVerifier.formatAcousticPnr(pnr);
            assertThat(acoustic).isEqualTo("Alpha-Bravo-7-Delta-Foxtrot-9");
        }

        @Test
        void formatsAllDigitsPnr() {
            String acoustic = AcousticPnrVerifier.formatAcousticPnr("012345");
            assertThat(acoustic).isEqualTo("0-1-2-3-4-5");
        }

        @Test
        void formatsAllLettersPnr() {
            String acoustic = AcousticPnrVerifier.formatAcousticPnr("SWISSQ");
            assertThat(acoustic).isEqualTo("Sierra-Whiskey-India-Sierra-Sierra-Quebec");
        }

        @Test
        void nullPnrRecordThrowsNullPointerException() {
            assertThatThrownBy(() -> AcousticPnrVerifier.formatAcousticPnr((Pnr) null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Acoustic Confirmation Prompt Generation")
    class ConfirmationPromptTests {

        @Test
        void generatesStandardConfirmationPrompt() {
            String prompt = AcousticPnrVerifier.generateConfirmationPrompt("A8F9X2");
            assertThat(prompt).isEqualTo("I heard Alpha-8-Foxtrot-9-Xray-2. Is that correct?");
        }

        @Test
        void generatesConfirmationPromptFromPnrRecord() {
            Pnr pnr = new Pnr("AB7DF9");
            String prompt = AcousticPnrVerifier.generateConfirmationPrompt(pnr);
            assertThat(prompt).isEqualTo("I heard Alpha-Bravo-7-Delta-Foxtrot-9. Is that correct?");
        }

        @Test
        void promptStrictlyAdheresToBrevityRule() {
            String prompt = AcousticPnrVerifier.generateConfirmationPrompt("A8F9X2");
            assertThat(TurnBrevityValidator.isValid(prompt)).isTrue();
            assertThat(TurnBrevityValidator.countWords(prompt)).isLessThanOrEqualTo(25);
        }
    }

    @Nested
    @DisplayName("Conversational Transcription Parsing")
    class TranscriptionParsingTests {

        @Test
        void parsesStandardNatoPhoneticWords() {
            Optional<Pnr> result =
                    AcousticPnrVerifier.tryParse("Alpha Eight Foxtrot Nine Xray Two");
            assertThat(result).isPresent();
            assertThat(result.get().value()).isEqualTo("A8F9X2");
        }

        @Test
        void parsesHyphenatedAcousticFormat() {
            Optional<Pnr> result = AcousticPnrVerifier.tryParse("Alpha-8-Foxtrot-9-Xray-2");
            assertThat(result).isPresent();
            assertThat(result.get().value()).isEqualTo("A8F9X2");
        }

        @Test
        void parsesConversationalUtteranceWithFillerWordsPerRfc0003() {
            // "Uh, yes, it's alpha eight, foxtrot nine, x-ray two."
            Pnr pnr =
                    AcousticPnrVerifier.parse(
                            "Uh, yes, it's alpha eight, foxtrot nine, x-ray two.");
            assertThat(pnr.value()).isEqualTo("A8F9X2");
        }

        @Test
        void parsesSpacedRawCharactersAndDigits() {
            Pnr pnr = AcousticPnrVerifier.parse("A 8 F 9 X 2");
            assertThat(pnr.value()).isEqualTo("A8F9X2");
        }

        @Test
        void parsesDirectSingleTokenPnr() {
            Pnr pnr = AcousticPnrVerifier.parse("a8f9x2");
            assertThat(pnr.value()).isEqualTo("A8F9X2");
        }

        @Test
        void parsesUtteranceWithNoisyPunctuationAndMixedCase() {
            Pnr pnr = AcousticPnrVerifier.parse("  alpha,  8 ; foxtrot  9 - xray . 2 ! ");
            assertThat(pnr.value()).isEqualTo("A8F9X2");
        }

        @Test
        void parsesAlternativeJulietAndXraySpellings() {
            Pnr pnr = AcousticPnrVerifier.parse("juliet 7 delta foxtrot 9 x-ray");
            assertThat(pnr.value()).isEqualTo("J7DF9X");
        }

        @Test
        void parsesWithBookingReferenceFillers() {
            Pnr pnr =
                    AcousticPnrVerifier.parse(
                            "Sure my booking reference is Bravo Charlie 4 Delta Echo 1 please");
            assertThat(pnr.value()).isEqualTo("BC4DE1");
        }

        @Test
        void incompleteTranscriptThrowsInvalidPnrTranscriptionException() {
            assertThatThrownBy(() -> AcousticPnrVerifier.parse("Alpha Bravo Charlie"))
                    .isInstanceOf(InvalidPnrTranscriptionException.class)
                    .hasMessageContaining("Unable to parse valid 6-character PNR");
            assertThat(AcousticPnrVerifier.tryParse("Alpha Bravo Charlie")).isEmpty();
        }

        @Test
        void excessTokensThrowsInvalidPnrTranscriptionException() {
            assertThatThrownBy(
                            () ->
                                    AcousticPnrVerifier.parse(
                                            "Alpha Bravo Charlie Delta Echo Foxtrot Golf"))
                    .isInstanceOf(InvalidPnrTranscriptionException.class);
            assertThat(AcousticPnrVerifier.tryParse("Alpha Bravo Charlie Delta Echo Foxtrot Golf"))
                    .isEmpty();
        }

        @Test
        void unrecognizedWordsFailSafely() {
            assertThatThrownBy(
                            () ->
                                    AcousticPnrVerifier.parse(
                                            "Alpha Bravo Foxtrot Pineapple Delta Echo"))
                    .isInstanceOf(InvalidPnrTranscriptionException.class);
            assertThat(AcousticPnrVerifier.tryParse("Alpha Bravo Foxtrot Pineapple Delta Echo"))
                    .isEmpty();
        }

        @Test
        void nullOrBlankReturnsEmpty() {
            assertThat(AcousticPnrVerifier.tryParse(null)).isEmpty();
            assertThat(AcousticPnrVerifier.tryParse("")).isEmpty();
            assertThat(AcousticPnrVerifier.tryParse("     ")).isEmpty();
            assertThatThrownBy(() -> AcousticPnrVerifier.parse(null))
                    .isInstanceOf(InvalidPnrTranscriptionException.class);
        }
    }

    @Nested
    @DisplayName("Instancio Randomized Property Tests")
    class RandomizedRoundTripTests {

        @Test
        void randomizedPnrRoundTripAcousticFormattingAndParsing() {
            for (int i = 0; i < 50; i++) {
                String randomPnr =
                        Instancio.of(String.class)
                                .generate(
                                        Select.root(), gen -> gen.string().length(6).alphaNumeric())
                                .create()
                                .toUpperCase();

                String formatted = AcousticPnrVerifier.formatAcousticPnr(randomPnr);
                Optional<Pnr> parsed = AcousticPnrVerifier.tryParse(formatted);

                assertThat(parsed)
                        .as("Failed round trip for PNR: %s (formatted: %s)", randomPnr, formatted)
                        .isPresent();
                assertThat(parsed.get().value()).isEqualTo(randomPnr);
            }
        }
    }
}
