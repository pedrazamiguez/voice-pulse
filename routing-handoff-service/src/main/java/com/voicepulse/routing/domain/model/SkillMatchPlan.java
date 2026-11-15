package com.voicepulse.routing.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable domain value object capturing the evaluated routing plan for a finalized disruption
 * ticket: resolved primary skill group, ordered candidate fallback desks, and composite priority
 * score.
 */
public record SkillMatchPlan(
        SkillGroup primarySkillGroup, List<SkillGroup> candidateOrder, int priorityScore) {

    public SkillMatchPlan {
        Objects.requireNonNull(primarySkillGroup, "primarySkillGroup cannot be null");
        Objects.requireNonNull(candidateOrder, "candidateOrder cannot be null");
        if (candidateOrder.isEmpty()) {
            throw new IllegalArgumentException("candidateOrder cannot be empty");
        }
        List<SkillGroup> immutableOrder = List.copyOf(candidateOrder);
        if (immutableOrder.getFirst() != primarySkillGroup) {
            throw new IllegalArgumentException(
                    "candidateOrder must start with primarySkillGroup: " + primarySkillGroup);
        }
        if (priorityScore < 0) {
            throw new IllegalArgumentException(
                    "priorityScore cannot be negative: " + priorityScore);
        }
        candidateOrder = immutableOrder;
    }
}
