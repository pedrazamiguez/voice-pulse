package com.voicepulse.conversation.domain.exception;

/**
 * Domain exception thrown when a generated bot conversational turn exceeds the maximum allowable
 * word ceiling (RFC-0003 and AGENTS.md §4).
 *
 * <p>Pure domain exception with zero framework dependencies.
 */
public class TurnBrevityExceededException extends IllegalArgumentException {

    private final int wordCount;
    private final int maxWords;
    private final String prompt;

    public TurnBrevityExceededException(int wordCount, int maxWords, String prompt) {
        super(
                "Turn prompt exceeds maximum "
                        + maxWords
                        + "-word limit: actual "
                        + wordCount
                        + " words > limit "
                        + maxWords
                        + " (prompt: \""
                        + (prompt == null ? "" : prompt)
                        + "\")");
        this.wordCount = wordCount;
        this.maxWords = maxWords;
        this.prompt = prompt == null ? "" : prompt;
    }

    public int wordCount() {
        return wordCount;
    }

    public int maxWords() {
        return maxWords;
    }

    public String prompt() {
        return prompt;
    }
}
