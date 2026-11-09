package com.voicepulse.conversation.infrastructure.ai.mcp.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.infrastructure.ai.mcp.dto.DisruptionTicketDto;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("DisruptionTicketMapper MapStruct Unit Tests")
class DisruptionTicketMapperTest {

    private final DisruptionTicketMapper mapper = Mappers.getMapper(DisruptionTicketMapper.class);

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CALL_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String PASSENGER = "Andrés Pedraza Míguez";
    private static final String PNR = "A8F9X2";
    private static final String FLIGHT = "SW-104";
    private static final String STATUS = "PENDING_HANDOFF";
    private static final Instant CREATED_AT = Instant.parse("2026-11-09T09:15:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-11-09T09:20:00Z");

    @Nested
    @DisplayName("DTO to Domain Mapping")
    class ToDomainTests {

        @Test
        @DisplayName("Should map complete DisruptionTicketDto to AirlineDisruptionTicket")
        void shouldMapCompleteDtoToDomain() {
            Map<String, Object> payload = Map.of("partySize", 2, "hotelRooms", 1);
            DisruptionTicketDto dto =
                    new DisruptionTicketDto(
                            TICKET_ID,
                            CALL_ID,
                            PASSENGER,
                            PNR,
                            FLIGHT,
                            "CANCELED_WEATHER",
                            "NEXT_AVAILABLE",
                            true,
                            false,
                            STATUS,
                            payload,
                            CREATED_AT,
                            UPDATED_AT);

            AirlineDisruptionTicket domain = mapper.toDomain(dto);

            assertThat(domain).isNotNull();
            assertThat(domain.ticketId()).isEqualTo(TICKET_ID);
            assertThat(domain.callId()).isEqualTo(CALL_ID);
            assertThat(domain.passengerName()).isEqualTo(PASSENGER);
            assertThat(domain.pnr()).isEqualTo(PNR);
            assertThat(domain.originalFlightNumber()).isEqualTo(FLIGHT);
            assertThat(domain.disruptionReason()).isEqualTo(DisruptionReason.CANCELED_WEATHER);
            assertThat(domain.rebookingPreference()).isEqualTo(RebookingPreference.NEXT_AVAILABLE);
            assertThat(domain.hotelRequired()).isTrue();
            assertThat(domain.specialAssistance()).isFalse();
            assertThat(domain.status()).isEqualTo(STATUS);
            assertThat(domain.payload()).containsEntry("partySize", 2);
            assertThat(domain.createdAt()).isEqualTo(CREATED_AT);
            assertThat(domain.updatedAt()).isEqualTo(UPDATED_AT);
        }

        @Test
        @DisplayName("Should return null when mapping null DTO to domain")
        void shouldReturnNullWhenDtoIsNull() {
            assertThat(mapper.toDomain(null)).isNull();
        }
    }

    @Nested
    @DisplayName("Domain to DTO Mapping")
    class ToDtoTests {

        @Test
        @DisplayName("Should map AirlineDisruptionTicket to DisruptionTicketDto")
        void shouldMapDomainToDto() {
            Map<String, Object> payload = Map.of("baggageChecked", true);
            AirlineDisruptionTicket domain =
                    new AirlineDisruptionTicket(
                            TICKET_ID,
                            CALL_ID,
                            PASSENGER,
                            PNR,
                            FLIGHT,
                            DisruptionReason.DELAYED_MAINTENANCE,
                            RebookingPreference.REFUND_REQUESTED,
                            false,
                            true,
                            STATUS,
                            payload,
                            CREATED_AT,
                            UPDATED_AT);

            DisruptionTicketDto dto = mapper.toDto(domain);

            assertThat(dto).isNotNull();
            assertThat(dto.ticketId()).isEqualTo(TICKET_ID);
            assertThat(dto.callId()).isEqualTo(CALL_ID);
            assertThat(dto.passengerName()).isEqualTo(PASSENGER);
            assertThat(dto.pnr()).isEqualTo(PNR);
            assertThat(dto.originalFlightNumber()).isEqualTo(FLIGHT);
            assertThat(dto.disruptionReason()).isEqualTo("DELAYED_MAINTENANCE");
            assertThat(dto.rebookingPreference()).isEqualTo("REFUND_REQUESTED");
            assertThat(dto.hotelRequired()).isFalse();
            assertThat(dto.specialAssistance()).isTrue();
            assertThat(dto.status()).isEqualTo(STATUS);
            assertThat(dto.payload()).containsEntry("baggageChecked", true);
            assertThat(dto.createdAt()).isEqualTo(CREATED_AT);
            assertThat(dto.updatedAt()).isEqualTo(UPDATED_AT);
        }

        @Test
        @DisplayName("Should return null when mapping null domain to DTO")
        void shouldReturnNullWhenDomainIsNull() {
            assertThat(mapper.toDto(null)).isNull();
        }
    }
}
