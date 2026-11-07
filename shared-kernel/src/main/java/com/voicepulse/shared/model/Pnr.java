package com.voicepulse.shared.model;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Passenger Name Record (PNR) booking reference.
 *
 * <p>Represents a 6-character alphanumeric airline reservation code per RFC-0003, with acoustic
 * NATO phonetic spelling capability for voice telephony confirmation.
 */
public record Pnr(String value) {

    private static final Pattern PNR_PATTERN = Pattern.compile("^[A-Z0-9]{6}$");

    private static final Map<Character, String> NATO_ALPHABET =
            Map.ofEntries(
                    Map.entry('A', "Alpha"),
                    Map.entry('B', "Bravo"),
                    Map.entry('C', "Charlie"),
                    Map.entry('D', "Delta"),
                    Map.entry('E', "Echo"),
                    Map.entry('F', "Foxtrot"),
                    Map.entry('G', "Golf"),
                    Map.entry('H', "Hotel"),
                    Map.entry('I', "India"),
                    Map.entry('J', "Juliett"),
                    Map.entry('K', "Kilo"),
                    Map.entry('L', "Lima"),
                    Map.entry('M', "Mike"),
                    Map.entry('N', "November"),
                    Map.entry('O', "Oscar"),
                    Map.entry('P', "Papa"),
                    Map.entry('Q', "Quebec"),
                    Map.entry('R', "Romeo"),
                    Map.entry('S', "Sierra"),
                    Map.entry('T', "Tango"),
                    Map.entry('U', "Uniform"),
                    Map.entry('V', "Victor"),
                    Map.entry('W', "Whiskey"),
                    Map.entry('X', "X-ray"),
                    Map.entry('Y', "Yankee"),
                    Map.entry('Z', "Zulu"),
                    Map.entry('0', "Zero"),
                    Map.entry('1', "One"),
                    Map.entry('2', "Two"),
                    Map.entry('3', "Three"),
                    Map.entry('4', "Four"),
                    Map.entry('5', "Five"),
                    Map.entry('6', "Six"),
                    Map.entry('7', "Seven"),
                    Map.entry('8', "Eight"),
                    Map.entry('9', "Nine"));

    public Pnr {
        Objects.requireNonNull(value, "PNR value cannot be null");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!PNR_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "PNR must be exactly 6 alphanumeric characters: " + value);
        }
        value = normalized;
    }

    /** Returns the NATO phonetic expansion for acoustic confirmation over telephony. */
    public String toNatoPhonetic() {
        return value.chars()
                .mapToObj(c -> NATO_ALPHABET.getOrDefault((char) c, String.valueOf((char) c)))
                .collect(Collectors.joining(" "));
    }

    @Override
    public String toString() {
        return value;
    }
}
