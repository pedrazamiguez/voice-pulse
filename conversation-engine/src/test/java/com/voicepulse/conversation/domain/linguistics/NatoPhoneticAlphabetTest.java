package com.voicepulse.conversation.domain.linguistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("NatoPhoneticAlphabet Domain Dictionary Tests")
class NatoPhoneticAlphabetTest {

    @Nested
    @DisplayName("Forward Mapping (Character to Phonetic Token)")
    class ForwardMappingTests {

        @ParameterizedTest(name = "Character ''{0}'' maps to NATO token ''{1}''")
        @CsvSource({
            "A, Alpha",
            "B, Bravo",
            "C, Charlie",
            "D, Delta",
            "E, Echo",
            "F, Foxtrot",
            "G, Golf",
            "H, Hotel",
            "I, India",
            "J, Juliett",
            "K, Kilo",
            "L, Lima",
            "M, Mike",
            "N, November",
            "O, Oscar",
            "P, Papa",
            "Q, Quebec",
            "R, Romeo",
            "S, Sierra",
            "T, Tango",
            "U, Uniform",
            "V, Victor",
            "W, Whiskey",
            "X, Xray",
            "Y, Yankee",
            "Z, Zulu"
        })
        void allLettersMapToCanonicalNatoTokens(char input, String expected) {
            assertThat(NatoPhoneticAlphabet.toPhonetic(input)).contains(expected);
            assertThat(NatoPhoneticAlphabet.toPhonetic(Character.toLowerCase(input)))
                    .contains(expected);
            assertThat(NatoPhoneticAlphabet.isSupported(input)).isTrue();
        }

        @ParameterizedTest(name = "Digit ''{0}'' maps to token ''{1}''")
        @CsvSource({
            "0, Zero", "1, One", "2, Two", "3, Three", "4, Four",
            "5, Five", "6, Six", "7, Seven", "8, Eight", "9, Nine"
        })
        void allDigitsMapToCanonicalTokens(char input, String expected) {
            assertThat(NatoPhoneticAlphabet.toPhonetic(input)).contains(expected);
            assertThat(NatoPhoneticAlphabet.isSupported(input)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(chars = {' ', '-', '_', '@', '#', '$', '%', '!', '?'})
        void unsupportedCharactersReturnEmpty(char unsupported) {
            assertThat(NatoPhoneticAlphabet.toPhonetic(unsupported)).isEmpty();
            assertThat(NatoPhoneticAlphabet.isSupported(unsupported)).isFalse();
        }
    }

    @Nested
    @DisplayName("Reverse Mapping (Phonetic Token to Character)")
    class ReverseMappingTests {

        @ParameterizedTest(name = "Token ''{0}'' parses to character ''{1}''")
        @CsvSource({
            "Alpha, A",
            "alpha, A",
            "ALPHA, A",
            "Bravo, B",
            "Charlie, C",
            "Delta, D",
            "Echo, E",
            "Foxtrot, F",
            "Golf, G",
            "Hotel, H",
            "India, I",
            "Juliett, J",
            "Juliet, J",
            "juliet, J",
            "JULIET, J",
            "Kilo, K",
            "Lima, L",
            "Mike, M",
            "November, N",
            "Oscar, O",
            "Papa, P",
            "Quebec, Q",
            "Romeo, R",
            "Sierra, S",
            "Tango, T",
            "Uniform, U",
            "Victor, V",
            "Whiskey, W",
            "Xray, X",
            "xray, X",
            "X-ray, X",
            "X-RAY, X",
            "Yankee, Y",
            "Zulu, Z"
        })
        void tokensParseToCharactersWithCaseAndSpellingTolerance(String token, char expectedChar) {
            assertThat(NatoPhoneticAlphabet.fromPhonetic(token)).contains(expectedChar);
            assertThat(NatoPhoneticAlphabet.isPhoneticToken(token)).isTrue();
        }

        @ParameterizedTest(name = "Digit token ''{0}'' parses to ''{1}''")
        @CsvSource({
            "Zero, 0", "One, 1", "Two, 2", "Three, 3", "Four, 4",
            "Five, 5", "Six, 6", "Seven, 7", "Eight, 8", "Nine, 9",
            "zero, 0", "ONE, 1", "8, 8", "0, 0", "9, 9"
        })
        void digitWordsAndDirectDigitsParseCorrectly(String token, char expectedChar) {
            assertThat(NatoPhoneticAlphabet.fromPhonetic(token)).contains(expectedChar);
            assertThat(NatoPhoneticAlphabet.isPhoneticToken(token)).isTrue();
        }

        @ParameterizedTest(name = "Single letter ''{0}'' parses directly to character")
        @CsvSource({"A, A", "a, A", "Z, Z", "m, M", "x, X"})
        void singleLetterTokensParseDirectly(String token, char expectedChar) {
            assertThat(NatoPhoneticAlphabet.fromPhonetic(token)).contains(expectedChar);
            assertThat(NatoPhoneticAlphabet.isPhoneticToken(token)).isTrue();
        }

        @Test
        void nullOrUnknownTokensReturnEmpty() {
            assertThat(NatoPhoneticAlphabet.fromPhonetic(null)).isEmpty();
            assertThat(NatoPhoneticAlphabet.fromPhonetic("")).isEmpty();
            assertThat(NatoPhoneticAlphabet.fromPhonetic("   ")).isEmpty();
            assertThat(NatoPhoneticAlphabet.fromPhonetic("Banana")).isEmpty();
            assertThat(NatoPhoneticAlphabet.fromPhonetic("Foxtrot123")).isEmpty();
            assertThat(NatoPhoneticAlphabet.isPhoneticToken("Airplane")).isFalse();
        }
    }

    @Nested
    @DisplayName("Telephony Acoustic Token Formatting")
    class AcousticTokenTests {

        @Test
        void lettersFormatToNatoWords() {
            assertThat(NatoPhoneticAlphabet.toAcousticToken('A')).isEqualTo("Alpha");
            assertThat(NatoPhoneticAlphabet.toAcousticToken('a')).isEqualTo("Alpha");
            assertThat(NatoPhoneticAlphabet.toAcousticToken('F')).isEqualTo("Foxtrot");
            assertThat(NatoPhoneticAlphabet.toAcousticToken('X')).isEqualTo("Xray");
        }

        @Test
        void digitsFormatToDigitCharacters() {
            assertThat(NatoPhoneticAlphabet.toAcousticToken('0')).isEqualTo("0");
            assertThat(NatoPhoneticAlphabet.toAcousticToken('8')).isEqualTo("8");
            assertThat(NatoPhoneticAlphabet.toAcousticToken('9')).isEqualTo("9");
        }

        @Test
        void unsupportedCharacterThrowsIllegalArgumentException() {
            assertThatThrownBy(() -> NatoPhoneticAlphabet.toAcousticToken('-'))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported character for acoustic verification: -");
        }
    }
}
