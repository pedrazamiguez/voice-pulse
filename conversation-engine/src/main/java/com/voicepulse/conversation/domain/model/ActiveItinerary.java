package com.voicepulse.conversation.domain.model;

import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain record representing a passenger's active disrupted flight itinerary retrieved
 * via {@code mcp-crm-server} (RFC-0003 §4.1).
 */
public record ActiveItinerary(
        FlightNumber flightNumber,
        String originAirport,
        String destinationAirport,
        DisruptionReason disruptionReason,
        Instant originalDeparture) {

    public ActiveItinerary {
        Objects.requireNonNull(flightNumber, "flightNumber must not be null");
        Objects.requireNonNull(originAirport, "originAirport must not be null");
        Objects.requireNonNull(destinationAirport, "destinationAirport must not be null");
        Objects.requireNonNull(disruptionReason, "disruptionReason must not be null");
        Objects.requireNonNull(originalDeparture, "originalDeparture must not be null");

        if (originAirport.isBlank()) {
            throw new IllegalArgumentException("originAirport must not be blank");
        }
        if (destinationAirport.isBlank()) {
            throw new IllegalArgumentException("destinationAirport must not be blank");
        }
    }
}
