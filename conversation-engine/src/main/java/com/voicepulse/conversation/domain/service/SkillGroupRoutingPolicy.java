package com.voicepulse.conversation.domain.service;

import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.conversation.domain.model.FrequentFlyerTier;
import com.voicepulse.shared.model.DisruptionReason;
import java.util.Objects;

/**
 * Pure domain routing policy resolving the target human operator skill group queue for disrupted
 * SkyWay Airlines passengers (RFC-0001 §4.4 & RFC-0003 §4.3).
 *
 * <p>Precedence order:
 *
 * <ol>
 *   <li>Loyalty tier: {@code GOLD} or {@code PLATINUM} &rarr; {@link #DISRUPTION_DESK_GOLD}
 *   <li>Accessibility: {@code specialAssistance == true} &rarr; {@link
 *       #DISRUPTION_DESK_ACCESSIBILITY}
 *   <li>Severity: {@code DisruptionReason.CANCELED_WEATHER} &rarr; {@link
 *       #DISRUPTION_DESK_PRIORITY}
 *   <li>Default fallback &rarr; {@link #DISRUPTION_DESK_STANDARD}
 * </ol>
 */
public class SkillGroupRoutingPolicy {

    public static final String DISRUPTION_DESK_GOLD = "DISRUPTION_DESK_GOLD";
    public static final String DISRUPTION_DESK_ACCESSIBILITY = "DISRUPTION_DESK_ACCESSIBILITY";
    public static final String DISRUPTION_DESK_PRIORITY = "DISRUPTION_DESK_PRIORITY";
    public static final String DISRUPTION_DESK_STANDARD = "DISRUPTION_DESK_STANDARD";

    /**
     * Resolves the target operator skill group department from passenger attributes and disruption
     * cause.
     *
     * @param frequentFlyerTier passenger frequent flyer tier
     * @param specialAssistance true if special assistance / accessibility handling is required
     * @param disruptionReason disruption root cause
     * @return target operator skill group identifier
     */
    public String resolveSkillGroup(
            FrequentFlyerTier frequentFlyerTier,
            boolean specialAssistance,
            DisruptionReason disruptionReason) {
        Objects.requireNonNull(frequentFlyerTier, "frequentFlyerTier must not be null");
        Objects.requireNonNull(disruptionReason, "disruptionReason must not be null");

        if (frequentFlyerTier == FrequentFlyerTier.GOLD
                || frequentFlyerTier == FrequentFlyerTier.PLATINUM) {
            return DISRUPTION_DESK_GOLD;
        }
        if (specialAssistance) {
            return DISRUPTION_DESK_ACCESSIBILITY;
        }
        if (disruptionReason == DisruptionReason.CANCELED_WEATHER) {
            return DISRUPTION_DESK_PRIORITY;
        }
        return DISRUPTION_DESK_STANDARD;
    }

    /**
     * Resolves the target operator skill group department from the retrieved CRM {@link
     * CustomerProfile} and compiled {@link AirlineDisruptionTicket}.
     *
     * @param customerProfile passenger profile from {@code mcp-crm-server}
     * @param ticket compiled disruption ticket
     * @return target operator skill group identifier
     */
    public String resolveSkillGroup(
            CustomerProfile customerProfile, AirlineDisruptionTicket ticket) {
        Objects.requireNonNull(customerProfile, "customerProfile must not be null");
        Objects.requireNonNull(ticket, "ticket must not be null");

        return resolveSkillGroup(
                customerProfile.frequentFlyerTier(),
                ticket.specialAssistance(),
                ticket.disruptionReason());
    }
}
