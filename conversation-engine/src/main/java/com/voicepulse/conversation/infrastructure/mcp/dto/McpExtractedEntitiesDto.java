package com.voicepulse.conversation.infrastructure.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Transport DTO representing the {@code extracted_entities} block sent to {@code mcp-ticket-server}
 * when finalizing a disruption ticket (RFC-0003 §4.2).
 */
public record McpExtractedEntitiesDto(
        @JsonProperty("passenger_name") String passengerName,
        @JsonProperty("original_flight_number") String originalFlightNumber,
        @JsonProperty("disruption_reason") String disruptionReason,
        @JsonProperty("rebooking_preference") String rebookingPreference,
        @JsonProperty("hotel_required") boolean hotelRequired,
        @JsonProperty("requires_accessibility") boolean requiresAccessibility,
        @JsonProperty("party_size") int partySize) {}
