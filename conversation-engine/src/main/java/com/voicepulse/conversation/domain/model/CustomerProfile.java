package com.voicepulse.conversation.domain.model;

import com.voicepulse.shared.model.Pnr;
import java.util.Objects;

/**
 * Immutable domain record representing a SkyWay Airlines passenger profile and active disrupted
 * itinerary retrieved via {@code mcp-crm-server} (RFC-0003 §4.1).
 */
public record CustomerProfile(
        Pnr pnr,
        String customerName,
        FrequentFlyerTier frequentFlyerTier,
        ActiveItinerary activeItinerary) {

    public CustomerProfile {
        Objects.requireNonNull(pnr, "pnr must not be null");
        Objects.requireNonNull(customerName, "customerName must not be null");
        Objects.requireNonNull(frequentFlyerTier, "frequentFlyerTier must not be null");
        Objects.requireNonNull(activeItinerary, "activeItinerary must not be null");

        if (customerName.isBlank()) {
            throw new IllegalArgumentException("customerName must not be blank");
        }
    }
}
