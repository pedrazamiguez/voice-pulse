package com.voicepulse.conversation.infrastructure.mcp.mapper;

import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.TicketCategory;
import com.voicepulse.conversation.domain.model.TicketPriority;
import com.voicepulse.conversation.domain.model.TicketSubmissionReceipt;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpExtractedEntitiesDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpFinalizeTicketRequestDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpFinalizeTicketResponseDto;
import com.voicepulse.shared.model.Pnr;
import java.util.Map;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Compile-time MapStruct mapper transforming {@link AirlineDisruptionTicket} domain records into
 * {@code mcp-ticket-server} transport DTOs and mapping response DTOs into {@link
 * TicketSubmissionReceipt}.
 *
 * <p>Enforces zero runtime reflection and strict field verification via {@link
 * ReportingPolicy#ERROR}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface McpTicketMapper {

    @Mapping(target = "category", expression = "java(resolveCategory(ticket))")
    @Mapping(target = "priority", expression = "java(resolvePriority(ticket))")
    @Mapping(target = "summary", expression = "java(buildSummary(ticket))")
    @Mapping(target = "extractedEntities", expression = "java(toExtractedEntities(ticket))")
    McpFinalizeTicketRequestDto toFinalizeRequest(AirlineDisruptionTicket ticket);

    @Mapping(target = "requiresAccessibility", source = "specialAssistance")
    @Mapping(target = "partySize", expression = "java(resolvePartySize(ticket))")
    McpExtractedEntitiesDto toExtractedEntities(AirlineDisruptionTicket ticket);

    TicketSubmissionReceipt toReceipt(McpFinalizeTicketResponseDto dto);

    default String mapUuid(UUID value) {
        return value == null ? null : value.toString();
    }

    default Pnr mapPnr(String pnr) {
        return pnr == null ? null : new Pnr(pnr);
    }

    default String resolveCategory(AirlineDisruptionTicket ticket) {
        Map<String, Object> payload = ticket.payload();
        if (payload != null && payload.get("category") instanceof String cat && !cat.isBlank()) {
            return cat;
        }
        return TicketCategory.resolve(ticket.hotelRequired(), ticket.rebookingPreference()).name();
    }

    default String resolvePriority(AirlineDisruptionTicket ticket) {
        Map<String, Object> payload = ticket.payload();
        if (payload != null && payload.get("priority") instanceof String prio && !prio.isBlank()) {
            return prio;
        }
        return TicketPriority.resolve(ticket.hotelRequired(), ticket.specialAssistance()).name();
    }

    default int resolvePartySize(AirlineDisruptionTicket ticket) {
        Map<String, Object> payload = ticket.payload();
        if (payload != null && payload.get("party_size") instanceof Number num) {
            return Math.max(1, num.intValue());
        }
        return 1;
    }

    default String buildSummary(AirlineDisruptionTicket ticket) {
        Map<String, Object> payload = ticket.payload();
        if (payload != null && payload.get("summary") instanceof String sum && !sum.isBlank()) {
            return sum;
        }
        return String.format(
                "Flight %s disrupted (%s). Passenger %s requests %s (hotel=%s, assistance=%s).",
                ticket.originalFlightNumber(),
                ticket.disruptionReason(),
                ticket.passengerName(),
                ticket.rebookingPreference(),
                ticket.hotelRequired(),
                ticket.specialAssistance());
    }
}
