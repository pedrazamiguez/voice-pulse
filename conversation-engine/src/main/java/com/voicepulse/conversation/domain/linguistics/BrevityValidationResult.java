package com.voicepulse.conversation.domain.linguistics;

import java.util.Objects;

/**
 * Immutable domain record capturing the evaluation result of a conversational turn against the
 * 25-word brevity invariant (RFC-0003 and AGENTS.md §4).
 *
 * @param isValid true if {@code wordCount <= maxWords}
 * @param wordCount actual count of whitespace-delimited words
 * @param maxWords maximum allowed words for this conversational turn
 * @param prompt the evaluated prompt text
 */
public record BrevityValidationResult(boolean isValid, int wordCount, int maxWords, String prompt) {

    public BrevityValidationResult {
        Objects.requireNonNull(prompt, "prompt cannot be null");
        if (wordCount < 0) {
            throw new IllegalArgumentException("wordCount must not be negative: " + wordCount);
        }
        if (maxWords < 0) {
            throw new IllegalArgumentException("maxWords must not be negative: " + maxWords);
        }
    }
}
