package com.voicepulse.conversation.domain.linguistics;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure domain dictionary and translator providing bi-directional mapping between alphanumeric
 * characters (A–Z, 0–9) and NATO phonetic spelling tokens for telephony acoustic verification.
 *
 * <p>Enforces zero framework dependencies per hexagonal domain invariants.
 */
public final class NatoPhoneticAlphabet {

    private static final Map<Character, String> CHAR_TO_NATO;
    private static final Map<String, Character> NATO_TO_CHAR;

    static {
        Map<Character, String> charToNato = new HashMap<>(36);
        charToNato.put('A', "Alpha");
        charToNato.put('B', "Bravo");
        charToNato.put('C', "Charlie");
        charToNato.put('D', "Delta");
        charToNato.put('E', "Echo");
        charToNato.put('F', "Foxtrot");
        charToNato.put('G', "Golf");
        charToNato.put('H', "Hotel");
        charToNato.put('I', "India");
        charToNato.put('J', "Juliett");
        charToNato.put('K', "Kilo");
        charToNato.put('L', "Lima");
        charToNato.put('M', "Mike");
        charToNato.put('N', "November");
        charToNato.put('O', "Oscar");
        charToNato.put('P', "Papa");
        charToNato.put('Q', "Quebec");
        charToNato.put('R', "Romeo");
        charToNato.put('S', "Sierra");
        charToNato.put('T', "Tango");
        charToNato.put('U', "Uniform");
        charToNato.put('V', "Victor");
        charToNato.put('W', "Whiskey");
        charToNato.put('X', "Xray");
        charToNato.put('Y', "Yankee");
        charToNato.put('Z', "Zulu");
        charToNato.put('0', "Zero");
        charToNato.put('1', "One");
        charToNato.put('2', "Two");
        charToNato.put('3', "Three");
        charToNato.put('4', "Four");
        charToNato.put('5', "Five");
        charToNato.put('6', "Six");
        charToNato.put('7', "Seven");
        charToNato.put('8', "Eight");
        charToNato.put('9', "Nine");
        CHAR_TO_NATO = Collections.unmodifiableMap(charToNato);

        Map<String, Character> natoToChar = new HashMap<>(64);
        charToNato.forEach((ch, word) -> natoToChar.put(word.toUpperCase(Locale.ROOT), ch));

        // Alternative spelling tolerances
        natoToChar.put("JULIET", 'J');
        natoToChar.put("X-RAY", 'X');

        // Raw single characters
        for (char c = 'A'; c <= 'Z'; c++) {
            natoToChar.put(String.valueOf(c), c);
        }
        for (char c = '0'; c <= '9'; c++) {
            natoToChar.put(String.valueOf(c), c);
        }

        NATO_TO_CHAR = Collections.unmodifiableMap(natoToChar);
    }

    private NatoPhoneticAlphabet() {
        // Pure domain utility
    }

    /**
     * Resolves an alphanumeric character to its canonical NATO phonetic token.
     *
     * @param character alphanumeric character (case-insensitive)
     * @return optional containing canonical phonetic token, or empty if unsupported
     */
    public static Optional<String> toPhonetic(char character) {
        char upper = Character.toUpperCase(character);
        return Optional.ofNullable(CHAR_TO_NATO.get(upper));
    }

    /**
     * Parses a phonetic token or single digit/letter into its normalized alphanumeric character.
     *
     * @param token NATO word or digit token (case-insensitive)
     * @return optional containing normalized character, or empty if unrecognized
     */
    public static Optional<Character> fromPhonetic(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalized = token.trim().toUpperCase(Locale.ROOT);
        return Optional.ofNullable(NATO_TO_CHAR.get(normalized));
    }

    /**
     * Checks whether the supplied token is a recognized phonetic token.
     *
     * @param token token candidate
     * @return true if token maps to a character
     */
    public static boolean isPhoneticToken(String token) {
        return fromPhonetic(token).isPresent();
    }

    /**
     * Checks whether the supplied character is supported by the phonetic alphabet.
     *
     * @param character candidate character
     * @return true if character is alphanumeric [A-Za-z0-9]
     */
    public static boolean isSupported(char character) {
        return toPhonetic(character).isPresent();
    }

    /**
     * Converts a character into its telephony acoustic token representation per RFC-0003 and
     * AGENTS.md §4: letters are expanded to NATO words (e.g., 'A' -> "Alpha"), while digits remain
     * numeric digit strings (e.g., '8' -> "8").
     *
     * @param character alphanumeric character
     * @return telephony acoustic string representation
     * @throws IllegalArgumentException if character is not alphanumeric
     */
    public static String toAcousticToken(char character) {
        char upper = Character.toUpperCase(character);
        if (Character.isDigit(upper)) {
            return String.valueOf(upper);
        }
        return toPhonetic(upper)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Unsupported character for acoustic verification: "
                                                + character));
    }
}
