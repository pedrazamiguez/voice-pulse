package com.voicepulse.conversation.infrastructure.mcp.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.TicketCategory;
import com.voicepulse.conversation.domain.model.TicketPriority;
import com.voicepulse.conversation.domain.model.TicketSubmissionReceipt;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpExtractedEntitiesDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpFinalizeTicketRequestDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpFinalizeTicketResponseDto;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.Map;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("McpTicketMapper MapStruct Unit Tests")
class McpTicketMapperTest {

    private final McpTicketMapper mapper = Mappers.getMapper(McpTicketMapper.class);

    @Nested
    @DisplayName("AirlineDisruptionTicket to McpFinalizeTicketRequestDto Mapping")
    class FinalizeRequestMappingTests {

        @Test
        @DisplayName("Should map AirlineDisruptionTicket to McpFinalizeTicketRequestDto")
        void shouldMapTicketToFinalizeRequestDto() {
            AirlineDisruptionTicket ticket =
                    Instancio.of(AirlineDisruptionTicket.class)
                            .set(field(AirlineDisruptionTicket::passengerName), "Andres")
                            .set(field(AirlineDisruptionTicket::pnr), "A8F9X2")
                            .set(field(AirlineDisruptionTicket::originalFlightNumber), "SW-104")
                            .set(
                                    field(AirlineDisruptionTicket::disruptionReason),
                                    DisruptionReason.CANCELED_WEATHER)
                            .set(
                                    field(AirlineDisruptionTicket::rebookingPreference),
                                    RebookingPreference.NEXT_AVAILABLE)
                            .set(field(AirlineDisruptionTicket::hotelRequired), true)
                            .set(field(AirlineDisruptionTicket::specialAssistance), true)
                            .set(field(AirlineDisruptionTicket::payload), Map.of("party_size", 2))
                            .create();

            McpFinalizeTicketRequestDto requestDto = mapper.toFinalizeRequest(ticket);

            assertThat(requestDto).isNotNull();
            assertThat(requestDto.ticketId()).isEqualTo(ticket.ticketId().toString());
            assertThat(requestDto.pnr()).isEqualTo("A8F9X2");
            assertThat(requestDto.category()).isEqualTo(TicketCategory.HOTEL_ACCOMMODATION.name());
            assertThat(requestDto.priority()).isEqualTo(TicketPriority.HIGH.name());
            assertThat(requestDto.summary())
                    .contains("SW-104")
                    .contains("CANCELED_WEATHER")
                    .contains("Andres")
                    .contains("NEXT_AVAILABLE");

            McpExtractedEntitiesDto entities = requestDto.extractedEntities();
            assertThat(entities).isNotNull();
            assertThat(entities.passengerName()).isEqualTo("Andres");
            assertThat(entities.originalFlightNumber()).isEqualTo("SW-104");
            assertThat(entities.disruptionReason()).isEqualTo("CANCELED_WEATHER");
            assertThat(entities.rebookingPreference()).isEqualTo("NEXT_AVAILABLE");
            assertThat(entities.hotelRequired()).isTrue();
            assertThat(entities.requiresAccessibility()).isTrue();
            assertThat(entities.partySize()).isEqualTo(2);
        }

        @Test
        @DisplayName(
                "Should derive REFUND_DISRUPTION and FLIGHT_REBOOKING categories and NORMAL"
                        + " priority")
        void shouldDeriveCategoriesAndNormalPriorityWhenPayloadEmpty() {
            AirlineDisruptionTicket refundTicket =
                    Instancio.of(AirlineDisruptionTicket.class)
                            .set(field(AirlineDisruptionTicket::pnr), "B7G8Y3")
                            .set(
                                    field(AirlineDisruptionTicket::rebookingPreference),
                                    RebookingPreference.REFUND_REQUESTED)
                            .set(field(AirlineDisruptionTicket::hotelRequired), false)
                            .set(field(AirlineDisruptionTicket::specialAssistance), false)
                            .set(field(AirlineDisruptionTicket::payload), Map.of())
                            .create();

            McpFinalizeTicketRequestDto refundDto = mapper.toFinalizeRequest(refundTicket);
            assertThat(refundDto.category()).isEqualTo(TicketCategory.REFUND_DISRUPTION.name());
            assertThat(refundDto.priority()).isEqualTo(TicketPriority.NORMAL.name());
            assertThat(refundDto.extractedEntities().partySize()).isEqualTo(1);

            AirlineDisruptionTicket rebookTicket =
                    Instancio.of(AirlineDisruptionTicket.class)
                            .set(field(AirlineDisruptionTicket::pnr), "C3K9M1")
                            .set(
                                    field(AirlineDisruptionTicket::rebookingPreference),
                                    RebookingPreference.NEXT_AVAILABLE)
                            .set(field(AirlineDisruptionTicket::hotelRequired), false)
                            .set(field(AirlineDisruptionTicket::specialAssistance), false)
                            .set(field(AirlineDisruptionTicket::payload), Map.of())
                            .create();

            McpFinalizeTicketRequestDto rebookDto = mapper.toFinalizeRequest(rebookTicket);
            assertThat(rebookDto.category()).isEqualTo(TicketCategory.FLIGHT_REBOOKING.name());
            assertThat(rebookDto.priority()).isEqualTo(TicketPriority.NORMAL.name());
        }

        @Test
        @DisplayName("Should preserve explicit category, priority, and summary from ticket payload")
        void shouldPreserveExplicitPayloadOverrides() {
            AirlineDisruptionTicket ticket =
                    Instancio.of(AirlineDisruptionTicket.class)
                            .set(field(AirlineDisruptionTicket::pnr), "A8F9X2")
                            .set(
                                    field(AirlineDisruptionTicket::payload),
                                    Map.of(
                                            "category", "CUSTOM_CATEGORY",
                                            "priority", "URGENT",
                                            "summary", "Custom agent triage summary"))
                            .create();

            McpFinalizeTicketRequestDto requestDto = mapper.toFinalizeRequest(ticket);

            assertThat(requestDto.category()).isEqualTo("CUSTOM_CATEGORY");
            assertThat(requestDto.priority()).isEqualTo("URGENT");
            assertThat(requestDto.summary()).isEqualTo("Custom agent triage summary");
        }
    }

    @Nested
    @DisplayName("McpFinalizeTicketResponseDto to TicketSubmissionReceipt Mapping")
    class ReceiptMappingTests {

        @Test
        @DisplayName("Should map McpFinalizeTicketResponseDto to TicketSubmissionReceipt")
        void shouldMapResponseDtoToReceipt() {
            Instant submittedAt = Instant.parse("2026-11-11T10:30:00Z");
            McpFinalizeTicketResponseDto responseDto =
                    Instancio.of(McpFinalizeTicketResponseDto.class)
                            .set(field(McpFinalizeTicketResponseDto::ticketId), "TKT-9921-X")
                            .set(field(McpFinalizeTicketResponseDto::pnr), "A8F9X2")
                            .set(
                                    field(McpFinalizeTicketResponseDto::category),
                                    TicketCategory.HOTEL_ACCOMMODATION.name())
                            .set(
                                    field(McpFinalizeTicketResponseDto::priority),
                                    TicketPriority.HIGH.name())
                            .set(field(McpFinalizeTicketResponseDto::status), "PENDING_HANDOFF")
                            .set(field(McpFinalizeTicketResponseDto::submittedAt), submittedAt)
                            .create();

            TicketSubmissionReceipt receipt = mapper.toReceipt(responseDto);

            assertThat(receipt).isNotNull();
            assertThat(receipt.ticketId()).isEqualTo("TKT-9921-X");
            assertThat(receipt.pnr()).isEqualTo(new Pnr("A8F9X2"));
            assertThat(receipt.category()).isEqualTo(TicketCategory.HOTEL_ACCOMMODATION.name());
            assertThat(receipt.priority()).isEqualTo(TicketPriority.HIGH.name());
            assertThat(receipt.status()).isEqualTo("PENDING_HANDOFF");
            assertThat(receipt.submittedAt()).isEqualTo(submittedAt);
        }

        @Test
        @DisplayName("Should return null when input records or scalars are null")
        void shouldReturnNullWhenInputsAreNull() {
            assertThat(mapper.toFinalizeRequest(null)).isNull();
            assertThat(mapper.toExtractedEntities(null)).isNull();
            assertThat(mapper.toReceipt(null)).isNull();
            assertThat(mapper.mapUuid(null)).isNull();
            assertThat(mapper.mapPnr(null)).isNull();
        }
    }

    @Nested
    @DisplayName("McpFinalizeTicketRequestDto Validation")
    class RequestDtoValidationTests {

        @Test
        @DisplayName("Should reject null or blank fields in McpFinalizeTicketRequestDto")
        void shouldRejectNullOrBlankFields() {
            McpExtractedEntitiesDto entities =
                    new McpExtractedEntitiesDto(
                            "Andres",
                            "SW-104",
                            "CANCELED_WEATHER",
                            "NEXT_AVAILABLE",
                            true,
                            false,
                            1);

            assertThatThrownBy(
                            () ->
                                    new McpFinalizeTicketRequestDto(
                                            null, "A8F9X2", "CAT", "HIGH", "Summary", entities))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ticketId must not be null");

            assertThatThrownBy(
                            () ->
                                    new McpFinalizeTicketRequestDto(
                                            "  ", "A8F9X2", "CAT", "HIGH", "Summary", entities))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ticketId must not be blank");

            assertThatThrownBy(
                            () ->
                                    new McpFinalizeTicketRequestDto(
                                            "TKT-1", " ", "CAT", "HIGH", "Summary", entities))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pnr must not be blank");

            assertThatThrownBy(
                            () ->
                                    new McpFinalizeTicketRequestDto(
                                            "TKT-1", "A8F9X2", "", "HIGH", "Summary", entities))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("category must not be blank");

            assertThatThrownBy(
                            () ->
                                    new McpFinalizeTicketRequestDto(
                                            "TKT-1", "A8F9X2", "CAT", " ", "Summary", entities))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("priority must not be blank");

            assertThatThrownBy(
                            () ->
                                    new McpFinalizeTicketRequestDto(
                                            "TKT-1", "A8F9X2", "CAT", "HIGH", "  ", entities))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("summary must not be blank");

            assertThatThrownBy(
                            () ->
                                    new McpFinalizeTicketRequestDto(
                                            "TKT-1", "A8F9X2", "CAT", "HIGH", "Summary", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("extractedEntities must not be null");
        }
    }
}
