package com.voicepulse.routing.domain.service;

import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.model.SkillMatchPlan;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.DisruptionReason;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Pure domain policy mapping finalized {@link TicketFormFilledEvent} attributes into a {@link
 * SkillMatchPlan} with primary skill group resolution, fallback candidate order, and priority
 * scoring per RFC-0003 §4.
 */
public class SkillGroupMatchingPolicy {

    private static final int SPECIAL_ASSISTANCE_BONUS = 25;
    private static final int HOTEL_REQUIRED_BONUS = 20;
    private static final int WEATHER_CANCELLATION_BONUS = 15;
    private static final int TECHNICAL_DIVERSION_BONUS = 10;
    private static final int CREW_REST_CANCELLATION_BONUS = 5;

    /**
     * Evaluates the complete skill matching and priority plan for an incoming disruption ticket.
     */
    public SkillMatchPlan evaluate(TicketFormFilledEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        SkillGroup primarySkillGroup = resolveTargetSkillGroup(event);
        List<SkillGroup> candidateOrder = buildCandidateOrder(primarySkillGroup, event);
        int priorityScore = computePriorityScore(event, primarySkillGroup);
        return new SkillMatchPlan(primarySkillGroup, candidateOrder, priorityScore);
    }

    /** Resolves and elevates the primary {@link SkillGroup} from ticket code and attributes. */
    public SkillGroup resolveTargetSkillGroup(TicketFormFilledEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        SkillGroup baseline = parseOrDeriveBaseline(event.targetSkillGroup());
        if (baseline == SkillGroup.DISRUPTION_DESK_GOLD) {
            return SkillGroup.DISRUPTION_DESK_GOLD;
        }
        if (event.specialAssistance()) {
            return SkillGroup.DISRUPTION_DESK_ACCESSIBILITY;
        }
        if (event.hotelRequired() && baseline == SkillGroup.DISRUPTION_DESK_STANDARD) {
            return SkillGroup.DISRUPTION_DESK_PRIORITY;
        }
        return baseline;
    }

    /** Returns the ordered candidate {@link SkillGroup} sequence to probe for available agents. */
    public List<SkillGroup> resolveCandidateOrder(TicketFormFilledEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        SkillGroup primarySkillGroup = resolveTargetSkillGroup(event);
        return buildCandidateOrder(primarySkillGroup, event);
    }

    /** Computes a deterministic non-negative queue priority score for Redis ZSET ordering. */
    public int computePriorityScore(TicketFormFilledEvent event, SkillGroup primarySkillGroup) {
        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(primarySkillGroup, "primarySkillGroup cannot be null");

        int score = primarySkillGroup.defaultPriorityScore();
        if (event.specialAssistance()) {
            score += SPECIAL_ASSISTANCE_BONUS;
        }
        if (event.hotelRequired()) {
            score += HOTEL_REQUIRED_BONUS;
        }
        score += computeReasonBonus(event.disruptionReason());
        return score;
    }

    private static List<SkillGroup> buildCandidateOrder(
            SkillGroup primarySkillGroup, TicketFormFilledEvent event) {
        Set<SkillGroup> orderedCandidates = new LinkedHashSet<>();
        orderedCandidates.add(primarySkillGroup);

        switch (primarySkillGroup) {
            case DISRUPTION_DESK_GOLD -> {
                if (event.specialAssistance()) {
                    orderedCandidates.add(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY);
                }
                orderedCandidates.add(SkillGroup.DISRUPTION_DESK_PRIORITY);
                orderedCandidates.add(SkillGroup.DISRUPTION_DESK_STANDARD);
            }
            case DISRUPTION_DESK_ACCESSIBILITY -> {
                orderedCandidates.add(SkillGroup.DISRUPTION_DESK_PRIORITY);
                orderedCandidates.add(SkillGroup.DISRUPTION_DESK_STANDARD);
            }
            case DISRUPTION_DESK_PRIORITY ->
                    orderedCandidates.add(SkillGroup.DISRUPTION_DESK_STANDARD);
            case DISRUPTION_DESK_STANDARD -> {
                // Standard callers remain in the standard skill pool to protect specialist
                // capacity.
            }
        }
        return List.copyOf(orderedCandidates);
    }

    private static SkillGroup parseOrDeriveBaseline(String targetSkillGroup) {
        String normalized = targetSkillGroup.trim().toUpperCase(Locale.ROOT);
        for (SkillGroup candidate : SkillGroup.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        if (normalized.contains("GOLD") || normalized.contains("PLATINUM")) {
            return SkillGroup.DISRUPTION_DESK_GOLD;
        }
        if (normalized.contains("ACCESS")) {
            return SkillGroup.DISRUPTION_DESK_ACCESSIBILITY;
        }
        if (normalized.contains("PRIORITY")) {
            return SkillGroup.DISRUPTION_DESK_PRIORITY;
        }
        return SkillGroup.DISRUPTION_DESK_STANDARD;
    }

    private static int computeReasonBonus(DisruptionReason reason) {
        return switch (reason) {
            case CANCELED_WEATHER -> WEATHER_CANCELLATION_BONUS;
            case DIVERTED_TECHNICAL -> TECHNICAL_DIVERSION_BONUS;
            case CANCELED_CREW_REST -> CREW_REST_CANCELLATION_BONUS;
            case DELAYED_MAINTENANCE -> 0;
        };
    }
}
