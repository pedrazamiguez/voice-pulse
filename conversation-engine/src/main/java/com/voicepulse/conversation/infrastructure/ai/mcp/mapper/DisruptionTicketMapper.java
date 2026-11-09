package com.voicepulse.conversation.infrastructure.ai.mcp.mapper;

import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.infrastructure.ai.mcp.dto.DisruptionTicketDto;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * MapStruct mapper for converting between MCP transport DTOs and domain {@link
 * AirlineDisruptionTicket}.
 *
 * <p>Enforces zero runtime reflection and compile-time field verification with {@link
 * ReportingPolicy#ERROR}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DisruptionTicketMapper {

    AirlineDisruptionTicket toDomain(DisruptionTicketDto dto);

    DisruptionTicketDto toDto(AirlineDisruptionTicket domain);
}
