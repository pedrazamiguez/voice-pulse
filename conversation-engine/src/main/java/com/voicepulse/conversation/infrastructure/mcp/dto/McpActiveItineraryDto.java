package com.voicepulse.conversation.infrastructure.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Transport DTO representing the {@code active_itinerary} block returned by {@code mcp-crm-server}
 * (RFC-0003 §4.1).
 */
public record McpActiveItineraryDto(
        @JsonProperty("flight_number") String flightNumber,
        @JsonProperty("origin") String origin,
        @JsonProperty("destination") String destination,
        @JsonProperty("status") String status,
        @JsonProperty("original_departure") Instant originalDeparture) {}
