package com.voicepulse.routing.infrastructure.persistence.mapper;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.persistence.model.CallSessionRow;
import com.voicepulse.routing.infrastructure.persistence.model.DisruptionTicketRow;
import com.voicepulse.routing.infrastructure.persistence.model.OperatorHandoffLogRow;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Compile-time MapStruct mapper transforming {@link TicketFormFilledEvent} and {@link
 * HandoffAssignment} domain records to/from PostgreSQL persistence row records.
 *
 * <p>Enforces zero runtime reflection and strict target field verification via {@link
 * ReportingPolicy#ERROR}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TicketPersistenceMapper {

    @Mapping(target = "ticketId", source = "event.ticketId")
    @Mapping(target = "callId", source = "event.callId")
    @Mapping(target = "pnr", source = "event.pnr")
    @Mapping(target = "passengerName", source = "event.passengerName")
    @Mapping(target = "originalFlightNumber", source = "event.originalFlightNumber")
    @Mapping(target = "disruptionReason", source = "event.disruptionReason")
    @Mapping(target = "rebookingPreference", source = "event.rebookingPreference")
    @Mapping(target = "hotelRequired", source = "event.hotelRequired")
    @Mapping(target = "specialAssistance", source = "event.specialAssistance")
    @Mapping(target = "status", expression = "java(resolveLifecycleStatus(initialState))")
    @Mapping(target = "priority", expression = "java(resolvePriority(event, skillGroup))")
    @Mapping(target = "summary", expression = "java(buildSummary(event))")
    @Mapping(target = "payload", source = "event.payload")
    @Mapping(target = "createdAt", source = "event.timestamp")
    @Mapping(target = "updatedAt", source = "event.timestamp")
    DisruptionTicketRow toTicketRow(
            TicketFormFilledEvent event, SkillGroup skillGroup, HandoffLifecycleState initialState);

    @Mapping(target = "callId", source = "event.callId")
    @Mapping(target = "pnr", source = "event.pnr")
    @Mapping(target = "callerNumber", expression = "java(extractCallerNumber(event))")
    @Mapping(target = "status", expression = "java(resolveLifecycleStatus(initialState))")
    @Mapping(target = "startTime", source = "event.timestamp")
    @Mapping(target = "endTime", expression = "java(extractEndTime(initialState))")
    @Mapping(target = "durationSeconds", ignore = true)
    @Mapping(target = "assignedOperatorId", expression = "java(extractOperatorId(initialState))")
    @Mapping(target = "metadata", expression = "java(buildSessionMetadata(event, skillGroup))")
    @Mapping(target = "createdAt", source = "event.timestamp")
    @Mapping(target = "updatedAt", source = "event.timestamp")
    CallSessionRow toCallSessionRow(
            TicketFormFilledEvent event, SkillGroup skillGroup, HandoffLifecycleState initialState);

    @Mapping(target = "callId", source = "assignment.callId")
    @Mapping(target = "pnr", ignore = true)
    @Mapping(target = "callerNumber", ignore = true)
    @Mapping(target = "status", constant = "ASSIGNED")
    @Mapping(target = "startTime", source = "assignment.assignedAt")
    @Mapping(target = "endTime", ignore = true)
    @Mapping(target = "durationSeconds", ignore = true)
    @Mapping(target = "assignedOperatorId", source = "assignment.operatorId")
    @Mapping(target = "metadata", expression = "java(buildAssignmentMetadata(assignment))")
    @Mapping(target = "createdAt", source = "assignment.assignedAt")
    @Mapping(target = "updatedAt", source = "assignment.assignedAt")
    CallSessionRow toCallSessionRow(HandoffAssignment assignment);

    @Mapping(target = "callId", source = "assignment.callId")
    @Mapping(target = "eventType", source = "eventType")
    @Mapping(target = "sourceState", source = "sourceState")
    @Mapping(target = "targetState", constant = "ASSIGNED")
    @Mapping(target = "operatorId", source = "assignment.operatorId")
    @Mapping(target = "details", expression = "java(buildAssignmentMetadata(assignment))")
    @Mapping(target = "occurredAt", source = "assignment.assignedAt")
    OperatorHandoffLogRow toAuditLogRow(
            HandoffAssignment assignment, String eventType, String sourceState);

    @Mapping(target = "callId", source = "row.callId")
    @Mapping(target = "ticketId", source = "row.ticketId")
    @Mapping(target = "passengerName", source = "row.passengerName")
    @Mapping(target = "pnr", source = "row.pnr")
    @Mapping(target = "originalFlightNumber", source = "row.originalFlightNumber")
    @Mapping(target = "disruptionReason", source = "row.disruptionReason")
    @Mapping(target = "rebookingPreference", source = "row.rebookingPreference")
    @Mapping(target = "hotelRequired", source = "row.hotelRequired")
    @Mapping(target = "specialAssistance", source = "row.specialAssistance")
    @Mapping(
            target = "targetSkillGroup",
            expression = "java(resolveTargetSkillGroup(targetSkillGroup))")
    @Mapping(target = "payload", source = "row.payload")
    @Mapping(target = "timestamp", source = "row.createdAt")
    TicketFormFilledEvent toTicketFormFilledEvent(DisruptionTicketRow row, String targetSkillGroup);

    default String mapUuidToString(UUID value) {
        return value == null ? null : value.toString();
    }

    default UUID mapStringToUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    default UUID mapCallIdToUuid(CallId callId) {
        return callId == null ? null : callId.value();
    }

    default CallId mapUuidToCallId(UUID uuid) {
        return uuid == null ? null : new CallId(uuid);
    }

    default String mapPnrToString(Pnr pnr) {
        return pnr == null ? null : pnr.value();
    }

    default Pnr mapStringToPnr(String pnr) {
        return pnr == null ? null : new Pnr(pnr);
    }

    default String mapFlightNumberToString(FlightNumber flightNumber) {
        return flightNumber == null ? null : flightNumber.value();
    }

    default FlightNumber mapStringToFlightNumber(String flightNumber) {
        return flightNumber == null ? null : new FlightNumber(flightNumber);
    }

    default String mapOperatorIdToString(OperatorId operatorId) {
        return operatorId == null ? null : operatorId.value();
    }

    default String resolveLifecycleStatus(HandoffLifecycleState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case HandoffLifecycleState.Queued ignored -> "QUEUED";
            case HandoffLifecycleState.Assigned ignored -> "ASSIGNED";
            case HandoffLifecycleState.AudioBridged ignored -> "AUDIO_BRIDGED";
            case HandoffLifecycleState.Completed ignored -> "COMPLETED";
            case HandoffLifecycleState.Abandoned ignored -> "ABANDONED";
        };
    }

    default String extractOperatorId(HandoffLifecycleState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case HandoffLifecycleState.Assigned assigned -> assigned.operatorId().value();
            case HandoffLifecycleState.AudioBridged bridged -> bridged.operatorId().value();
            case HandoffLifecycleState.Completed completed -> completed.operatorId().value();
            case HandoffLifecycleState.Queued ignored -> null;
            case HandoffLifecycleState.Abandoned ignored -> null;
        };
    }

    default Instant extractEndTime(HandoffLifecycleState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case HandoffLifecycleState.Completed completed -> completed.completedAt();
            case HandoffLifecycleState.Abandoned abandoned -> abandoned.abandonedAt();
            case HandoffLifecycleState.Queued ignored -> null;
            case HandoffLifecycleState.Assigned ignored -> null;
            case HandoffLifecycleState.AudioBridged ignored -> null;
        };
    }

    default String resolvePriority(TicketFormFilledEvent event, SkillGroup skillGroup) {
        Map<String, Object> payload = event.payload();
        if (payload.get("priority") instanceof String priority && !priority.isBlank()) {
            return priority.trim();
        }
        boolean prioritySkill =
                skillGroup == SkillGroup.DISRUPTION_DESK_GOLD
                        || skillGroup == SkillGroup.DISRUPTION_DESK_ACCESSIBILITY
                        || skillGroup == SkillGroup.DISRUPTION_DESK_PRIORITY;
        return (event.hotelRequired() || event.specialAssistance() || prioritySkill)
                ? "HIGH"
                : "NORMAL";
    }

    default String buildSummary(TicketFormFilledEvent event) {
        Map<String, Object> payload = event.payload();
        if (payload.get("summary") instanceof String summary && !summary.isBlank()) {
            return summary.trim();
        }
        return String.format(
                "Flight %s disrupted (%s). Passenger %s requests %s (hotel=%s, assistance=%s).",
                event.originalFlightNumber(),
                event.disruptionReason(),
                event.passengerName(),
                event.rebookingPreference(),
                event.hotelRequired(),
                event.specialAssistance());
    }

    default String extractCallerNumber(TicketFormFilledEvent event) {
        Object callerNumber = event.payload().get("caller_number");
        if (callerNumber instanceof String number && !number.isBlank()) {
            return number.trim();
        }
        return null;
    }

    default Map<String, Object> buildSessionMetadata(
            TicketFormFilledEvent event, SkillGroup skillGroup) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetSkillGroup", event.targetSkillGroup());
        if (skillGroup != null) {
            metadata.put("resolvedSkillGroup", skillGroup.name());
        }
        return Collections.unmodifiableMap(metadata);
    }

    default Map<String, Object> buildAssignmentMetadata(HandoffAssignment assignment) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("ticketId", assignment.ticketId().toString());
        metadata.put("skillGroup", assignment.skillGroup().name());
        metadata.put("bridgeEndpoint", assignment.bridgeEndpoint());
        return Collections.unmodifiableMap(metadata);
    }

    default String resolveTargetSkillGroup(String targetSkillGroup) {
        if (targetSkillGroup != null && !targetSkillGroup.isBlank()) {
            return targetSkillGroup.trim();
        }
        return SkillGroup.DISRUPTION_DESK_STANDARD.name();
    }
}
