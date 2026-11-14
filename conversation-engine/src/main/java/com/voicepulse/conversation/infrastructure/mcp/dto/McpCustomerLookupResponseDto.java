package com.voicepulse.conversation.infrastructure.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Transport response DTO returned by the {@code lookup_customer} tool on {@code mcp-crm-server}
 * (RFC-0003 §4.1).
 */
public record McpCustomerLookupResponseDto(
        @JsonProperty("pnr") String pnr,
        @JsonProperty("customer_name") String customerName,
        @JsonProperty("frequent_flyer_tier") String frequentFlyerTier,
        @JsonProperty("active_itinerary") McpActiveItineraryDto activeItinerary) {}
