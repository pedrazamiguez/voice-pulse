package com.voicepulse.conversation.infrastructure.mcp.mapper;

import com.voicepulse.conversation.domain.model.ActiveItinerary;
import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.conversation.domain.model.FrequentFlyerTier;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpActiveItineraryDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpCustomerLookupResponseDto;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.util.Locale;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Compile-time MapStruct mapper transforming {@code mcp-crm-server} transport DTOs into pure domain
 * {@link CustomerProfile} and {@link ActiveItinerary} records.
 *
 * <p>Enforces zero runtime reflection and strict field verification via {@link
 * ReportingPolicy#ERROR}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface McpCrmMapper {

    CustomerProfile toDomain(McpCustomerLookupResponseDto dto);

    @Mapping(target = "pnr", source = "pnr")
    @Mapping(target = "customerName", source = "dto.customerName")
    @Mapping(target = "frequentFlyerTier", source = "dto.frequentFlyerTier")
    @Mapping(target = "activeItinerary", source = "dto.activeItinerary")
    CustomerProfile toDomain(McpCustomerLookupResponseDto dto, Pnr pnr);

    @Mapping(target = "originAirport", source = "origin")
    @Mapping(target = "destinationAirport", source = "destination")
    @Mapping(target = "disruptionReason", source = "status")
    ActiveItinerary toDomain(McpActiveItineraryDto dto);

    default Pnr mapPnr(String pnr) {
        return pnr == null ? null : new Pnr(pnr);
    }

    default FlightNumber mapFlightNumber(String flightNumber) {
        return flightNumber == null ? null : new FlightNumber(flightNumber);
    }

    default FrequentFlyerTier mapTier(String frequentFlyerTier) {
        if (frequentFlyerTier == null) {
            return null;
        }
        return FrequentFlyerTier.valueOf(frequentFlyerTier.trim().toUpperCase(Locale.ROOT));
    }

    default DisruptionReason mapDisruptionReason(String status) {
        if (status == null) {
            return null;
        }
        String normalized =
                status.trim().toUpperCase(Locale.ROOT).replace("CANCELLED_", "CANCELED_");
        return DisruptionReason.valueOf(normalized);
    }
}
