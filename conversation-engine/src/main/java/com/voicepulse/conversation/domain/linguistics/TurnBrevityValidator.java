package com.voicepulse.conversation.domain.linguistics;

import com.voicepulse.conversation.domain.exception.TurnBrevityExceededException;
import java.util.regex.Pattern;

/**
 * Pure domain validator enforcing the strict 25-word maximum ceiling per conversational turn for
 * the SkyWay Airlines Disruption Hotline (RFC-0003 and AGENTS.md §4).
 *
 * <p>Prevents verbose bot monologues, reduces telephony Time-to-First-Audio (TTFA) latency, and
 * guards conversational pacing.
 */
public final class TurnBrevityValidator {

    public static final int MAX_TURN_WORDS = 25;
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private TurnBrevityValidator() {
        // Pure domain utility
    }

    /**
     * Counts the whitespace-delimited words in the provided conversational turn prompt.
     *
     * @param prompt prompt text (null, empty, or whitespace returns 0)
     * @return word count
     */
    public static int countWords(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return 0;
        }
        return WHITESPACE_PATTERN.split(prompt.trim()).length;
    }

    /**
     * Evaluates whether the prompt satisfies the 25-word brevity ceiling.
     *
     * @param prompt prompt text
     * @return true if word count is less than or equal to 25
     */
    public static boolean isValid(String prompt) {
        return countWords(prompt) <= MAX_TURN_WORDS;
    }

    /**
     * Asserts that the prompt does not exceed the 25-word ceiling.
     *
     * @param prompt prompt text
     * @throws TurnBrevityExceededException if word count exceeds 25
     */
    public static void validate(String prompt) {
        int wordCount = countWords(prompt);
        if (wordCount > MAX_TURN_WORDS) {
            throw new TurnBrevityExceededException(wordCount, MAX_TURN_WORDS, prompt);
        }
    }

    /**
     * Evaluates the prompt and returns a comprehensive validation record.
     *
     * @param prompt prompt text
     * @return immutable validation result record
     */
    public static BrevityValidationResult validateWithDetails(String prompt) {
        String safePrompt = prompt == null ? "" : prompt;
        int wordCount = countWords(safePrompt);
        boolean valid = wordCount <= MAX_TURN_WORDS;
        return new BrevityValidationResult(valid, wordCount, MAX_TURN_WORDS, safePrompt);
    }

    /**
     * Truncates the prompt to at most 25 words if it exceeds the brevity ceiling.
     *
     * @param prompt prompt text
     * @return original prompt if within ceiling, or truncated prompt containing first 25 words
     */
    public static String truncateToLimit(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return prompt == null ? "" : prompt.trim();
        }

        String trimmed = prompt.trim();
        String[] words = WHITESPACE_PATTERN.split(trimmed);
        if (words.length <= MAX_TURN_WORDS) {
            return trimmed;
        }

        StringBuilder truncated = new StringBuilder(trimmed.length());
        for (int i = 0; i < MAX_TURN_WORDS; i++) {
            if (i > 0) {
                truncated.append(' ');
            }
            truncated.append(words[i]);
        }
        return truncated.toString();
    }
}
