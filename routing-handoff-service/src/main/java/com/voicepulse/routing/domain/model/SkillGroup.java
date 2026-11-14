package com.voicepulse.routing.domain.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Operator skill queues and priority routing tiers for the SkyWay Airlines Disruption Hotline
 * (RFC-0003 §4).
 */
public enum SkillGroup {
    DISRUPTION_DESK_GOLD(100),
    DISRUPTION_DESK_ACCESSIBILITY(90),
    DISRUPTION_DESK_PRIORITY(80),
    DISRUPTION_DESK_STANDARD(50);

    private final int defaultPriorityScore;

    SkillGroup(int defaultPriorityScore) {
        this.defaultPriorityScore = defaultPriorityScore;
    }

    /** Returns the baseline queue priority weight for this skill group. */
    public int defaultPriorityScore() {
        return defaultPriorityScore;
    }

    /**
     * Parses a skill group code string (trimmed and normalized to uppercase).
     *
     * @param code raw skill group code
     * @return matching {@link SkillGroup}
     */
    public static SkillGroup fromCode(String code) {
        Objects.requireNonNull(code, "skillGroup code cannot be null");
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("skillGroup code cannot be blank");
        }
        try {
            return SkillGroup.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown skillGroup code: " + code, ex);
        }
    }
}
