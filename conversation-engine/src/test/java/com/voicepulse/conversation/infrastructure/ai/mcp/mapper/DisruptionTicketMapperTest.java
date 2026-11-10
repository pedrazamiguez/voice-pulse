package com.voicepulse.conversation.infrastructure.ai.mcp.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.infrastructure.ai.mcp.dto.DisruptionTicketDto;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.RebookingPreference;
import java.util.Map;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("DisruptionTicketMapper MapStruct Unit Tests")
class DisruptionTicketMapperTest {

    private final DisruptionTicketMapper mapper = Mappers.getMapper(DisruptionTicketMapper.class);

    @Nested
    @DisplayName("DTO to Domain Mapping")
    class ToDomainTests {

        @Test
        @DisplayName("Should map complete DisruptionTicketDto to AirlineDisruptionTicket")
        void shouldMapCompleteDtoToDomain() {
            DisruptionTicketDto dto =
                    Instancio.of(DisruptionTicketDto.class)
                            .generate(
                                    field(DisruptionTicketDto::pnr),
                                    gen -> gen.string().length(6).alphaNumeric())
                            .set(field(DisruptionTicketDto::disruptionReason), "CANCELED_WEATHER")
                            .set(field(DisruptionTicketDto::rebookingPreference), "NEXT_AVAILABLE")
                            .set(
                                    field(DisruptionTicketDto::payload),
                                    Map.of("partySize", 2, "hotelRooms", 1))
                            .create();

            AirlineDisruptionTicket domain = mapper.toDomain(dto);

            assertThat(domain).isNotNull();
            assertThat(domain.ticketId()).isEqualTo(dto.ticketId());
            assertThat(domain.callId()).isEqualTo(dto.callId());
            assertThat(domain.passengerName()).isEqualTo(dto.passengerName());
            assertThat(domain.pnr()).isEqualTo(dto.pnr());
            assertThat(domain.originalFlightNumber()).isEqualTo(dto.originalFlightNumber());
            assertThat(domain.disruptionReason()).isEqualTo(DisruptionReason.CANCELED_WEATHER);
            assertThat(domain.rebookingPreference()).isEqualTo(RebookingPreference.NEXT_AVAILABLE);
            assertThat(domain.hotelRequired()).isEqualTo(dto.hotelRequired());
            assertThat(domain.specialAssistance()).isEqualTo(dto.specialAssistance());
            assertThat(domain.status()).isEqualTo(dto.status());
            assertThat(domain.payload()).isEqualTo(dto.payload());
            assertThat(domain.createdAt()).isEqualTo(dto.createdAt());
            assertThat(domain.updatedAt()).isEqualTo(dto.updatedAt());
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
            AirlineDisruptionTicket domain =
                    Instancio.of(AirlineDisruptionTicket.class)
                            .generate(
                                    field(AirlineDisruptionTicket::pnr),
                                    gen -> gen.string().length(6).alphaNumeric())
                            .set(
                                    field(AirlineDisruptionTicket::disruptionReason),
                                    DisruptionReason.DELAYED_MAINTENANCE)
                            .set(
                                    field(AirlineDisruptionTicket::rebookingPreference),
                                    RebookingPreference.REFUND_REQUESTED)
                            .set(
                                    field(AirlineDisruptionTicket::payload),
                                    Map.of("baggageChecked", true))
                            .create();

            DisruptionTicketDto dto = mapper.toDto(domain);

            assertThat(dto).isNotNull();
            assertThat(dto.ticketId()).isEqualTo(domain.ticketId());
            assertThat(dto.callId()).isEqualTo(domain.callId());
            assertThat(dto.passengerName()).isEqualTo(domain.passengerName());
            assertThat(dto.pnr()).isEqualTo(domain.pnr());
            assertThat(dto.originalFlightNumber()).isEqualTo(domain.originalFlightNumber());
            assertThat(dto.disruptionReason()).isEqualTo("DELAYED_MAINTENANCE");
            assertThat(dto.rebookingPreference()).isEqualTo("REFUND_REQUESTED");
            assertThat(dto.hotelRequired()).isEqualTo(domain.hotelRequired());
            assertThat(dto.specialAssistance()).isEqualTo(domain.specialAssistance());
            assertThat(dto.status()).isEqualTo(domain.status());
            assertThat(dto.payload()).isEqualTo(domain.payload());
            assertThat(dto.createdAt()).isEqualTo(domain.createdAt());
            assertThat(dto.updatedAt()).isEqualTo(domain.updatedAt());
        }

        @Test
        @DisplayName("Should return null when mapping null domain to DTO")
        void shouldReturnNullWhenDomainIsNull() {
            assertThat(mapper.toDto(null)).isNull();
        }
    }
}
