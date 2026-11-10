package com.voicepulse.conversation.domain.linguistics;

import com.voicepulse.conversation.domain.exception.InvalidPnrTranscriptionException;
import com.voicepulse.shared.model.Pnr;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure domain service and verifier for SkyWay Airlines Passenger Name Records (PNRs).
 *
 * <p>Enforces 6-character alphanumeric validation, formats acoustic confirmation prompts (e.g.,
 * "Alpha-8-Foxtrot-9-Xray-2"), and parses transcribed conversational speech back into normalized
 * {@link Pnr} records per RFC-0003 and AGENTS.md §4.
 */
public final class AcousticPnrVerifier {

    private static final Pattern PNR_PATTERN = Pattern.compile("^[A-Z0-9]{6}$");
    private static final Pattern TOKEN_DELIMITERS = Pattern.compile("[\\s\\-]+");
    private static final Pattern APOSTROPHE_PATTERN = Pattern.compile("['’]");
    private static final Pattern PUNCTUATION_CLEANER = Pattern.compile("[,;\\.!\\?:\"\\(\\)]");
    private static final Pattern XRAY_HYPHEN_PATTERN = Pattern.compile("(?i)\\bx-ray\\b");

    private static final Set<String> FILLER_WORDS =
            Set.of(
                    "UH",
                    "UM",
                    "YES",
                    "YEAH",
                    "YEP",
                    "SURE",
                    "MY",
                    "PNR",
                    "IS",
                    "IT",
                    "ITS",
                    "LETS",
                    "CODE",
                    "BOOKING",
                    "REFERENCE",
                    "PLEASE",
                    "THE",
                    "THAT",
                    "THATS",
                    "HERE",
                    "OK",
                    "OKAY");

    private AcousticPnrVerifier() {
        // Pure domain utility
    }

    /**
     * Validates that the provided string represents a valid 6-character alphanumeric PNR.
     *
     * @param rawPnr raw PNR string
     * @return validated, normalized {@link Pnr}
     * @throws IllegalArgumentException if null or not matching 6 alphanumeric characters
     */
    public static Pnr validate(String rawPnr) {
        return new Pnr(rawPnr);
    }

    /**
     * Formats an acoustic phonetic representation of the PNR where letters are rendered as
     * canonical NATO phonetic tokens and digits are rendered as numeric characters, joined by
     * hyphens (e.g., "Alpha-8-Foxtrot-9-Xray-2").
     *
     * @param pnr domain PNR record
     * @return hyphen-separated acoustic representation
     */
    public static String formatAcousticPnr(Pnr pnr) {
        Objects.requireNonNull(pnr, "pnr cannot be null");
        return formatAcousticPnr(pnr.value());
    }

    /**
     * Formats an acoustic phonetic representation of a raw 6-character PNR string.
     *
     * @param rawPnr raw PNR string
     * @return hyphen-separated acoustic representation
     * @throws IllegalArgumentException if rawPnr is null or invalid
     */
    public static String formatAcousticPnr(String rawPnr) {
        Pnr pnr = validate(rawPnr);
        return pnr.value()
                .chars()
                .mapToObj(c -> NatoPhoneticAlphabet.toAcousticToken((char) c))
                .collect(Collectors.joining("-"));
    }

    /**
     * Generates a complete acoustic confirmation prompt for telephony playback (e.g., "I heard
     * Alpha-8-Foxtrot-9-Xray-2. Is that correct?").
     *
     * @param pnr domain PNR record
     * @return acoustic confirmation prompt adhering to the 25-word brevity rule
     */
    public static String generateConfirmationPrompt(Pnr pnr) {
        return "I heard " + formatAcousticPnr(pnr) + ". Is that correct?";
    }

    /**
     * Generates a complete acoustic confirmation prompt from a raw PNR string.
     *
     * @param rawPnr raw PNR string
     * @return acoustic confirmation prompt adhering to the 25-word brevity rule
     * @throws IllegalArgumentException if rawPnr is null or invalid
     */
    public static String generateConfirmationPrompt(String rawPnr) {
        return "I heard " + formatAcousticPnr(rawPnr) + ". Is that correct?";
    }

    /**
     * Parses and normalizes a transcribed conversational utterance into a 6-character {@link Pnr}.
     *
     * <p>Handles NATO phonetic tokens, raw digits/letters, digit words, conversational filler
     * words, and irregular spacing/punctuation.
     *
     * @param transcribedText raw transcribed speech
     * @return optional containing normalized Pnr, or empty if resolution fails
     */
    public static Optional<Pnr> tryParse(String transcribedText) {
        if (transcribedText == null || transcribedText.isBlank()) {
            return Optional.empty();
        }

        // Strip apostrophes so contractions like "it's" become "its" without isolating 's'
        String textWithoutApostrophes = APOSTROPHE_PATTERN.matcher(transcribedText).replaceAll("");

        // Normalize "x-ray" to "xray" so hyphen tokenization preserves it
        String normalizedText =
                XRAY_HYPHEN_PATTERN.matcher(textWithoutApostrophes).replaceAll("xray");

        // Clean punctuation to whitespace
        String cleanedText = PUNCTUATION_CLEANER.matcher(normalizedText).replaceAll(" ");

        String[] rawTokens = TOKEN_DELIMITERS.split(cleanedText.trim());
        StringBuilder resolvedChars = new StringBuilder(6);

        for (String rawToken : rawTokens) {
            if (rawToken == null || rawToken.isBlank()) {
                continue;
            }

            String token = rawToken.trim().toUpperCase(Locale.ROOT);

            // Skip common conversational fillers
            if (FILLER_WORDS.contains(token)) {
                continue;
            }

            // Resolve via NATO phonetic dictionary (supports NATO words, digit words, single chars)
            Optional<Character> characterOpt = NatoPhoneticAlphabet.fromPhonetic(token);
            if (characterOpt.isPresent()) {
                resolvedChars.append(characterOpt.get());
                continue;
            }

            // Direct 6-character PNR token match (e.g., "A8F9X2") when not a known NATO word
            if (resolvedChars.isEmpty() && PNR_PATTERN.matcher(token).matches()) {
                return Optional.of(new Pnr(token));
            }

            // If token is unrecognized, fail parsing immediately for safety
            return Optional.empty();
        }

        if (resolvedChars.length() == 6 && PNR_PATTERN.matcher(resolvedChars).matches()) {
            return Optional.of(new Pnr(resolvedChars.toString()));
        }

        return Optional.empty();
    }

    /**
     * Parses transcribed speech into a {@link Pnr}, throwing an exception upon failure.
     *
     * @param transcribedText raw transcribed speech
     * @return normalized 6-character Pnr
     * @throws InvalidPnrTranscriptionException if unable to resolve exactly 6 alphanumeric chars
     */
    public static Pnr parse(String transcribedText) {
        return tryParse(transcribedText)
                .orElseThrow(
                        () ->
                                new InvalidPnrTranscriptionException(
                                        transcribedText,
                                        "Expected exactly 6 valid alphanumeric PNR characters"));
    }
}
