package com.voicepulse.conversation.infrastructure.mcp.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.conversation.domain.model.OperatorHandoffReceipt;
import com.voicepulse.conversation.domain.model.OperatorQueueStatus;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpQueueStatusRequestDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpQueueStatusResponseDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpTransferRequestDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpTransferResponseDto;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.UUID;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("McpRoutingMapper MapStruct Unit Tests")
class McpRoutingMapperTest {

    private final McpRoutingMapper mapper = Mappers.getMapper(McpRoutingMapper.class);

    @Nested
    @DisplayName("Queue Status Mapping")
    class QueueStatusMappingTests {

        @Test
        @DisplayName("Should map department string to McpQueueStatusRequestDto")
        void shouldMapDepartmentToRequestDto() {
            McpQueueStatusRequestDto dto = mapper.toQueueStatusRequest("DISRUPTION_DESK_GOLD");

            assertThat(dto).isNotNull();
            assertThat(dto.department()).isEqualTo("DISRUPTION_DESK_GOLD");
        }

        @Test
        @DisplayName("Should map McpQueueStatusResponseDto to OperatorQueueStatus")
        void shouldMapQueueStatusResponseDtoToDomain() {
            McpQueueStatusResponseDto dto =
                    Instancio.of(McpQueueStatusResponseDto.class)
                            .set(
                                    field(McpQueueStatusResponseDto::department),
                                    "DISRUPTION_DESK_GOLD")
                            .generate(
                                    field(McpQueueStatusResponseDto::availableOperators),
                                    gen -> gen.ints().range(1, 20))
                            .generate(
                                    field(McpQueueStatusResponseDto::estimatedWaitSeconds),
                                    gen -> gen.ints().range(0, 300))
                            .generate(
                                    field(McpQueueStatusResponseDto::queuePosition),
                                    gen -> gen.ints().range(0, 50))
                            .create();

            OperatorQueueStatus domain = mapper.toQueueStatus(dto);

            assertThat(domain).isNotNull();
            assertThat(domain.department()).isEqualTo("DISRUPTION_DESK_GOLD");
            assertThat(domain.availableOperators()).isEqualTo(dto.availableOperators());
            assertThat(domain.estimatedWaitSeconds()).isEqualTo(dto.estimatedWaitSeconds());
            assertThat(domain.queuePosition()).isEqualTo(dto.queuePosition());
        }
    }

    @Nested
    @DisplayName("Transfer Request & Handoff Receipt Mapping")
    class TransferMappingTests {

        @Test
        @DisplayName("Should map CallId, department, and ticketId to McpTransferRequestDto")
        void shouldMapTransferRequest() {
            CallId callId = CallId.generate();
            UUID ticketId = UUID.randomUUID();

            McpTransferRequestDto dto =
                    mapper.toTransferRequest(callId, "DISRUPTION_DESK_PRIORITY", ticketId);

            assertThat(dto).isNotNull();
            assertThat(dto.callId()).isEqualTo(callId.toString());
            assertThat(dto.department()).isEqualTo("DISRUPTION_DESK_PRIORITY");
            assertThat(dto.ticketId()).isEqualTo(ticketId.toString());
        }

        @Test
        @DisplayName("Should map full McpTransferResponseDto to OperatorHandoffReceipt")
        void shouldMapFullTransferResponseToHandoffReceipt() {
            CallId callId = CallId.generate();
            UUID ticketId = UUID.randomUUID();
            Instant dispatchedAt = Instant.parse("2026-11-11T10:45:00Z");

            McpTransferResponseDto dto =
                    Instancio.of(McpTransferResponseDto.class)
                            .set(field(McpTransferResponseDto::callId), callId.toString())
                            .set(field(McpTransferResponseDto::ticketId), ticketId.toString())
                            .set(field(McpTransferResponseDto::department), "DISRUPTION_DESK_GOLD")
                            .set(field(McpTransferResponseDto::status), "QUEUED")
                            .set(field(McpTransferResponseDto::estimatedWaitSeconds), 120)
                            .set(field(McpTransferResponseDto::queuePosition), 14)
                            .set(field(McpTransferResponseDto::dispatchedAt), dispatchedAt)
                            .create();

            OperatorHandoffReceipt receipt = mapper.toHandoffReceipt(dto);

            assertThat(receipt).isNotNull();
            assertThat(receipt.callId()).isEqualTo(callId);
            assertThat(receipt.ticketId()).isEqualTo(ticketId);
            assertThat(receipt.department()).isEqualTo("DISRUPTION_DESK_GOLD");
            assertThat(receipt.status()).isEqualTo("QUEUED");
            assertThat(receipt.estimatedWaitSeconds()).isEqualTo(120);
            assertThat(receipt.queuePosition()).isEqualTo(14);
            assertThat(receipt.dispatchedAt()).isEqualTo(dispatchedAt);
        }

        @Test
        @DisplayName(
                "Should populate missing metadata from call context for minimal RFC-0003 §4.3"
                        + " transfer payload")
        void shouldMapMinimalTransferResponseWithFallbackContext() {
            CallId fallbackCallId = CallId.generate();
            UUID fallbackTicketId = UUID.randomUUID();
            Instant fallbackDispatchedAt = Instant.parse("2026-11-11T11:00:00Z");

            McpTransferResponseDto minimalDto =
                    new McpTransferResponseDto(null, null, null, "QUEUED", 120, 14, null);

            OperatorHandoffReceipt receipt =
                    mapper.toHandoffReceipt(
                            minimalDto,
                            fallbackCallId,
                            "DISRUPTION_DESK_GOLD",
                            fallbackTicketId,
                            fallbackDispatchedAt);

            assertThat(receipt).isNotNull();
            assertThat(receipt.callId()).isEqualTo(fallbackCallId);
            assertThat(receipt.ticketId()).isEqualTo(fallbackTicketId);
            assertThat(receipt.department()).isEqualTo("DISRUPTION_DESK_GOLD");
            assertThat(receipt.status()).isEqualTo("QUEUED");
            assertThat(receipt.estimatedWaitSeconds()).isEqualTo(120);
            assertThat(receipt.queuePosition()).isEqualTo(14);
            assertThat(receipt.dispatchedAt()).isEqualTo(fallbackDispatchedAt);
        }

        @Test
        @DisplayName("Should return null when null inputs are supplied")
        void shouldReturnNullWhenInputsAreNull() {
            assertThat(mapper.toQueueStatusRequest(null)).isNull();
            assertThat(mapper.toQueueStatus(null)).isNull();
            assertThat(mapper.toHandoffReceipt(null)).isNull();
            assertThat(mapper.mapCallId((CallId) null)).isNull();
            assertThat(mapper.mapCallId((String) null)).isNull();
            assertThat(mapper.mapUuid((UUID) null)).isNull();
            assertThat(mapper.mapUuid((String) null)).isNull();
        }
    }

    @Nested
    @DisplayName("Request DTO Validation")
    class RequestDtoValidationTests {

        @Test
        @DisplayName("Should reject null or blank department in McpQueueStatusRequestDto")
        void shouldValidateQueueStatusRequestDto() {
            assertThatThrownBy(() -> new McpQueueStatusRequestDto(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("department must not be null");

            assertThatThrownBy(() -> new McpQueueStatusRequestDto("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("department must not be blank");
        }

        @Test
        @DisplayName("Should reject null or blank fields in McpTransferRequestDto")
        void shouldValidateTransferRequestDto() {
            assertThatThrownBy(() -> new McpTransferRequestDto(null, "DEPT", "TKT-1"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("callId must not be null");

            assertThatThrownBy(() -> new McpTransferRequestDto("  ", "DEPT", "TKT-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("callId must not be blank");

            assertThatThrownBy(() -> new McpTransferRequestDto("CALL-1", null, "TKT-1"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("department must not be null");

            assertThatThrownBy(() -> new McpTransferRequestDto("CALL-1", " ", "TKT-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("department must not be blank");

            assertThatThrownBy(() -> new McpTransferRequestDto("CALL-1", "DEPT", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ticketId must not be null");

            assertThatThrownBy(() -> new McpTransferRequestDto("CALL-1", "DEPT", "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ticketId must not be blank");
        }
    }
}
