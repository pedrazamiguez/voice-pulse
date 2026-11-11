package com.voicepulse.conversation.domain.model;

/**
 * SkyWay Airlines frequent flyer loyalty tiers per RFC-0003 §4.1.
 *
 * <p>Determines priority operator queue routing during mass travel disruptions.
 */
public enum FrequentFlyerTier {
    STANDARD,
    SILVER,
    GOLD,
    PLATINUM
}
