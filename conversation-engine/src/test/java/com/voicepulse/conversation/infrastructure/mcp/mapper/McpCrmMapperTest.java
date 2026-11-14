package com.voicepulse.conversation.infrastructure.mcp.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.conversation.domain.model.FrequentFlyerTier;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpActiveItineraryDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpCustomerLookupRequestDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpCustomerLookupResponseDto;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("McpCrmMapper MapStruct Unit Tests")
class McpCrmMapperTest {

    private final McpCrmMapper mapper = Mappers.getMapper(McpCrmMapper.class);

    @Nested
    @DisplayName("CustomerProfile Mapping")
    class CustomerProfileMappingTests {

        @Test
        @DisplayName("Should map complete McpCustomerLookupResponseDto to CustomerProfile")
        void shouldMapCompleteDtoToCustomerProfile() {
            McpActiveItineraryDto itineraryDto =
                    Instancio.of(McpActiveItineraryDto.class)
                            .set(field(McpActiveItineraryDto::flightNumber), "SW-104")
                            .set(field(McpActiveItineraryDto::origin), "CTU")
                            .set(field(McpActiveItineraryDto::destination), "MAD")
                            .set(field(McpActiveItineraryDto::status), "CANCELED_WEATHER")
                            .set(
                                    field(McpActiveItineraryDto::originalDeparture),
                                    Instant.parse("2026-11-01T01:45:00Z"))
                            .create();

            McpCustomerLookupResponseDto dto =
                    Instancio.of(McpCustomerLookupResponseDto.class)
                            .generate(
                                    field(McpCustomerLookupResponseDto::pnr),
                                    gen -> gen.string().length(6).alphaNumeric())
                            .set(field(McpCustomerLookupResponseDto::customerName), "Andres")
                            .set(field(McpCustomerLookupResponseDto::frequentFlyerTier), "Gold")
                            .set(field(McpCustomerLookupResponseDto::activeItinerary), itineraryDto)
                            .create();

            CustomerProfile domain = mapper.toDomain(dto);

            assertThat(domain).isNotNull();
            assertThat(domain.pnr()).isEqualTo(new Pnr(dto.pnr()));
            assertThat(domain.customerName()).isEqualTo("Andres");
            assertThat(domain.frequentFlyerTier()).isEqualTo(FrequentFlyerTier.GOLD);
            assertThat(domain.activeItinerary()).isNotNull();
            assertThat(domain.activeItinerary().flightNumber())
                    .isEqualTo(new FlightNumber("SW-104"));
            assertThat(domain.activeItinerary().originAirport()).isEqualTo("CTU");
            assertThat(domain.activeItinerary().destinationAirport()).isEqualTo("MAD");
            assertThat(domain.activeItinerary().disruptionReason())
                    .isEqualTo(DisruptionReason.CANCELED_WEATHER);
            assertThat(domain.activeItinerary().originalDeparture())
                    .isEqualTo(Instant.parse("2026-11-01T01:45:00Z"));
        }

        @Test
        @DisplayName(
                "Should normalize CANCELLED_WEATHER spelling and case-insensitive loyalty tiers")
        void shouldNormalizeCancelledSpellingAndTierCase() {
            McpActiveItineraryDto itineraryDto =
                    new McpActiveItineraryDto(
                            "SW-208",
                            "MAD",
                            "JFK",
                            "CANCELLED_WEATHER",
                            Instant.parse("2026-11-02T09:15:00Z"));
            McpCustomerLookupResponseDto dto =
                    new McpCustomerLookupResponseDto("B7G8Y3", "Elena", "platinum", itineraryDto);

            CustomerProfile domain = mapper.toDomain(dto);

            assertThat(domain.frequentFlyerTier()).isEqualTo(FrequentFlyerTier.PLATINUM);
            assertThat(domain.activeItinerary().disruptionReason())
                    .isEqualTo(DisruptionReason.CANCELED_WEATHER);
        }

        @Test
        @DisplayName("Should return null when input DTOs or scalar values are null")
        void shouldReturnNullWhenInputsAreNull() {
            assertThat(mapper.toDomain((McpCustomerLookupResponseDto) null)).isNull();
            assertThat(mapper.toDomain((McpActiveItineraryDto) null)).isNull();
            assertThat(mapper.mapPnr(null)).isNull();
            assertThat(mapper.mapFlightNumber(null)).isNull();
            assertThat(mapper.mapTier(null)).isNull();
            assertThat(mapper.mapDisruptionReason(null)).isNull();
        }
    }

    @Nested
    @DisplayName("McpCustomerLookupRequestDto Validation")
    class RequestDtoValidationTests {

        @Test
        @DisplayName("Should reject null or blank PNR in McpCustomerLookupRequestDto")
        void shouldRejectNullOrBlankPnr() {
            assertThatThrownBy(() -> new McpCustomerLookupRequestDto(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("pnr must not be null");

            assertThatThrownBy(() -> new McpCustomerLookupRequestDto("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pnr must not be blank");
        }
    }
}
