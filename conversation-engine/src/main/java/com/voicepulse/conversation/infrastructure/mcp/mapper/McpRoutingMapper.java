package com.voicepulse.conversation.infrastructure.mcp.mapper;

import com.voicepulse.conversation.domain.model.OperatorHandoffReceipt;
import com.voicepulse.conversation.domain.model.OperatorQueueStatus;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpQueueStatusRequestDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpQueueStatusResponseDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpTransferRequestDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpTransferResponseDto;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Compile-time MapStruct mapper transforming {@code mcp-telephony-routing} transport DTOs to and
 * from pure domain {@link OperatorQueueStatus} and {@link OperatorHandoffReceipt} records.
 *
 * <p>Enforces zero runtime reflection and strict field verification via {@link
 * ReportingPolicy#ERROR}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface McpRoutingMapper {

    McpQueueStatusRequestDto toQueueStatusRequest(String department);

    OperatorQueueStatus toQueueStatus(McpQueueStatusResponseDto dto);

    McpTransferRequestDto toTransferRequest(CallId callId, String department, UUID ticketId);

    OperatorHandoffReceipt toHandoffReceipt(McpTransferResponseDto dto);

    @Mapping(target = "callId", expression = "java(resolveCallId(dto, fallbackCallId))")
    @Mapping(target = "ticketId", expression = "java(resolveTicketId(dto, fallbackTicketId))")
    @Mapping(target = "department", expression = "java(resolveDepartment(dto, fallbackDepartment))")
    @Mapping(target = "status", source = "dto.status")
    @Mapping(target = "estimatedWaitSeconds", source = "dto.estimatedWaitSeconds")
    @Mapping(target = "queuePosition", source = "dto.queuePosition")
    @Mapping(
            target = "dispatchedAt",
            expression = "java(resolveDispatchedAt(dto, fallbackDispatchedAt))")
    OperatorHandoffReceipt toHandoffReceipt(
            McpTransferResponseDto dto,
            CallId fallbackCallId,
            String fallbackDepartment,
            UUID fallbackTicketId,
            Instant fallbackDispatchedAt);

    default String mapCallId(CallId callId) {
        return callId == null ? null : callId.toString();
    }

    default CallId mapCallId(String callId) {
        return (callId == null || callId.isBlank()) ? null : new CallId(UUID.fromString(callId));
    }

    default String mapUuid(UUID value) {
        return value == null ? null : value.toString();
    }

    default UUID mapUuid(String value) {
        return (value == null || value.isBlank()) ? null : UUID.fromString(value);
    }

    default CallId resolveCallId(McpTransferResponseDto dto, CallId fallbackCallId) {
        if (dto != null && dto.callId() != null && !dto.callId().isBlank()) {
            return new CallId(UUID.fromString(dto.callId()));
        }
        return fallbackCallId;
    }

    default UUID resolveTicketId(McpTransferResponseDto dto, UUID fallbackTicketId) {
        if (dto != null && dto.ticketId() != null && !dto.ticketId().isBlank()) {
            return UUID.fromString(dto.ticketId());
        }
        return fallbackTicketId;
    }

    default String resolveDepartment(McpTransferResponseDto dto, String fallbackDepartment) {
        if (dto != null && dto.department() != null && !dto.department().isBlank()) {
            return dto.department();
        }
        return fallbackDepartment;
    }

    default Instant resolveDispatchedAt(McpTransferResponseDto dto, Instant fallbackDispatchedAt) {
        if (dto != null && dto.dispatchedAt() != null) {
            return dto.dispatchedAt();
        }
        return fallbackDispatchedAt;
    }
}
