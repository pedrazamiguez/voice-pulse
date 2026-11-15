package com.voicepulse.routing.infrastructure.ws.mapper;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.ws.config.OperatorConsoleProperties;
import com.voicepulse.routing.infrastructure.ws.dto.HandoffStateDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorAssignmentPayloadDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorPresenceDto;
import com.voicepulse.routing.infrastructure.ws.dto.QueueOverviewDto;
import com.voicepulse.routing.infrastructure.ws.dto.QueuedTicketDto;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Compile-time MapStruct mapper transforming pure domain presence, queue, ticket, and handoff
 * lifecycle models into Operator Web Console WebSocket/REST DTOs.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OperatorConsoleMapper {

    @Mapping(target = "operatorId", source = "presence.operatorId")
    @Mapping(target = "displayName", source = "presence.displayName")
    @Mapping(target = "status", expression = "java(presence.status().name())")
    @Mapping(target = "skillGroups", source = "presence.skills")
    @Mapping(target = "activeCallId", source = "presence.activeCallId")
    @Mapping(target = "lastHeartbeatAt", source = "presence.lastHeartbeatAt")
    OperatorPresenceDto toPresenceDto(OperatorPresence presence);

    @Mapping(target = "callId", source = "item.ticketEvent.callId")
    @Mapping(target = "ticketId", source = "item.ticketEvent.ticketId")
    @Mapping(target = "passengerName", source = "item.ticketEvent.passengerName")
    @Mapping(target = "pnr", source = "item.ticketEvent.pnr")
    @Mapping(target = "originalFlightNumber", source = "item.ticketEvent.originalFlightNumber")
    @Mapping(
            target = "disruptionReason",
            expression = "java(item.ticketEvent().disruptionReason().name())")
    @Mapping(
            target = "rebookingPreference",
            expression = "java(item.ticketEvent().rebookingPreference().name())")
    @Mapping(target = "hotelRequired", source = "item.ticketEvent.hotelRequired")
    @Mapping(target = "specialAssistance", source = "item.ticketEvent.specialAssistance")
    @Mapping(target = "skillGroup", expression = "java(item.skillGroup().name())")
    @Mapping(target = "priorityScore", source = "item.priorityScore")
    @Mapping(target = "enqueuedAt", source = "item.enqueuedAt")
    @Mapping(target = "eventTimestamp", source = "item.ticketEvent.timestamp")
    @Mapping(target = "summary", expression = "java(extractor.extractSummary(item.ticketEvent()))")
    @Mapping(
            target = "priority",
            expression = "java(extractor.extractPriority(item.ticketEvent(), item.skillGroup()))")
    @Mapping(
            target = "category",
            expression = "java(extractor.extractCategory(item.ticketEvent()))")
    QueuedTicketDto toQueuedTicketDto(
            QueuedTicketItem item, @Context TranscriptContextExtractor extractor);

    @Mapping(target = "callId", source = "assignment.callId")
    @Mapping(target = "ticketId", source = "assignment.ticketId")
    @Mapping(target = "operatorId", source = "assignment.operatorId")
    @Mapping(target = "skillGroup", expression = "java(assignment.skillGroup().name())")
    @Mapping(target = "bridgeEndpoint", source = "assignment.bridgeEndpoint")
    @Mapping(
            target = "bridgeUrl",
            expression = "java(consoleProperties.resolveBridgeUrl(assignment.bridgeEndpoint()))")
    @Mapping(target = "assignedAt", source = "assignment.assignedAt")
    @Mapping(target = "passengerName", source = "ticketEvent.passengerName")
    @Mapping(target = "pnr", source = "ticketEvent.pnr")
    @Mapping(target = "originalFlightNumber", source = "ticketEvent.originalFlightNumber")
    @Mapping(
            target = "disruptionReason",
            expression = "java(ticketEvent.disruptionReason().name())")
    @Mapping(
            target = "rebookingPreference",
            expression = "java(ticketEvent.rebookingPreference().name())")
    @Mapping(target = "hotelRequired", source = "ticketEvent.hotelRequired")
    @Mapping(target = "specialAssistance", source = "ticketEvent.specialAssistance")
    @Mapping(target = "summary", expression = "java(extractor.extractSummary(ticketEvent))")
    @Mapping(
            target = "priority",
            expression = "java(extractor.extractPriority(ticketEvent, assignment.skillGroup()))")
    @Mapping(target = "category", expression = "java(extractor.extractCategory(ticketEvent))")
    @Mapping(
            target = "callerNumber",
            expression = "java(extractor.extractCallerNumber(ticketEvent))")
    @Mapping(target = "transcript", expression = "java(extractor.extractTranscript(ticketEvent))")
    @Mapping(target = "metadata", expression = "java(extractor.extractMetadata(ticketEvent))")
    OperatorAssignmentPayloadDto toAssignmentPayload(
            HandoffAssignment assignment,
            TicketFormFilledEvent ticketEvent,
            OperatorConsoleProperties consoleProperties,
            @Context TranscriptContextExtractor extractor);

    @Mapping(target = "callId", source = "ticketEvent.callId")
    @Mapping(target = "ticketId", source = "ticketEvent.ticketId")
    @Mapping(target = "operatorId", ignore = true)
    @Mapping(target = "skillGroup", source = "ticketEvent.targetSkillGroup")
    @Mapping(target = "bridgeEndpoint", ignore = true)
    @Mapping(target = "bridgeUrl", ignore = true)
    @Mapping(target = "assignedAt", source = "ticketEvent.timestamp")
    @Mapping(target = "passengerName", source = "ticketEvent.passengerName")
    @Mapping(target = "pnr", source = "ticketEvent.pnr")
    @Mapping(target = "originalFlightNumber", source = "ticketEvent.originalFlightNumber")
    @Mapping(
            target = "disruptionReason",
            expression = "java(ticketEvent.disruptionReason().name())")
    @Mapping(
            target = "rebookingPreference",
            expression = "java(ticketEvent.rebookingPreference().name())")
    @Mapping(target = "hotelRequired", source = "ticketEvent.hotelRequired")
    @Mapping(target = "specialAssistance", source = "ticketEvent.specialAssistance")
    @Mapping(target = "summary", expression = "java(extractor.extractSummary(ticketEvent))")
    @Mapping(
            target = "priority",
            expression =
                    "java(extractor.extractPriority(ticketEvent,"
                            + " resolveSkillGroupOrNull(ticketEvent.targetSkillGroup())))")
    @Mapping(target = "category", expression = "java(extractor.extractCategory(ticketEvent))")
    @Mapping(
            target = "callerNumber",
            expression = "java(extractor.extractCallerNumber(ticketEvent))")
    @Mapping(target = "transcript", expression = "java(extractor.extractTranscript(ticketEvent))")
    @Mapping(target = "metadata", expression = "java(extractor.extractMetadata(ticketEvent))")
    OperatorAssignmentPayloadDto toTicketPayload(
            TicketFormFilledEvent ticketEvent, @Context TranscriptContextExtractor extractor);

    /** Projects sealed {@link HandoffLifecycleState} hierarchy via exhaustive pattern matching. */
    default HandoffStateDto toHandoffStateDto(
            HandoffLifecycleState state, OperatorConsoleProperties consoleProperties) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case HandoffLifecycleState.Queued queued ->
                    new HandoffStateDto(
                            "QUEUED",
                            queued.callId().toString(),
                            queued.ticketId().toString(),
                            null,
                            queued.skillGroup().name(),
                            queued.queuePosition(),
                            null,
                            null,
                            null,
                            null,
                            queued.queuedAt());
            case HandoffLifecycleState.Assigned assigned ->
                    new HandoffStateDto(
                            "ASSIGNED",
                            assigned.callId().toString(),
                            assigned.ticketId().toString(),
                            assigned.operatorId().value(),
                            assigned.skillGroup().name(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            assigned.assignedAt());
            case HandoffLifecycleState.AudioBridged bridged ->
                    new HandoffStateDto(
                            "AUDIO_BRIDGED",
                            bridged.callId().toString(),
                            bridged.ticketId().toString(),
                            bridged.operatorId().value(),
                            null,
                            null,
                            bridged.bridgeEndpoint(),
                            consoleProperties == null
                                    ? null
                                    : consoleProperties.resolveBridgeUrl(bridged.bridgeEndpoint()),
                            null,
                            null,
                            bridged.bridgedAt());
            case HandoffLifecycleState.Completed completed ->
                    new HandoffStateDto(
                            "COMPLETED",
                            completed.callId().toString(),
                            completed.ticketId().toString(),
                            completed.operatorId().value(),
                            null,
                            null,
                            null,
                            null,
                            completed.resolutionNotes(),
                            null,
                            completed.completedAt());
            case HandoffLifecycleState.Abandoned abandoned ->
                    new HandoffStateDto(
                            "ABANDONED",
                            abandoned.callId().toString(),
                            abandoned.ticketId().toString(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            abandoned.reason(),
                            abandoned.abandonedAt());
        };
    }

    /** Builds a {@link QueueOverviewDto} aggregating per-skill depths and queued call items. */
    default QueueOverviewDto toQueueOverviewDto(
            String selectedSkillGroup,
            Map<SkillGroup, Long> depthsBySkillGroup,
            List<QueuedTicketItem> queuedItems,
            Instant generatedAt,
            TranscriptContextExtractor extractor) {
        Map<String, Long> mappedDepths = new LinkedHashMap<>();
        long totalQueued = 0L;
        if (depthsBySkillGroup != null) {
            for (Map.Entry<SkillGroup, Long> entry : depthsBySkillGroup.entrySet()) {
                long depth = entry.getValue() == null ? 0L : Math.max(0L, entry.getValue());
                mappedDepths.put(entry.getKey().name(), depth);
                totalQueued += depth;
            }
        }
        List<QueuedTicketDto> queuedDtos =
                queuedItems == null
                        ? List.of()
                        : queuedItems.stream()
                                .map(item -> toQueuedTicketDto(item, extractor))
                                .toList();
        if (mappedDepths.isEmpty() && !queuedDtos.isEmpty()) {
            totalQueued = queuedDtos.size();
        }
        return new QueueOverviewDto(
                selectedSkillGroup,
                totalQueued,
                Collections.unmodifiableMap(mappedDepths),
                queuedDtos,
                generatedAt);
    }

    default String mapOperatorIdToString(OperatorId operatorId) {
        return operatorId == null ? null : operatorId.value();
    }

    default String mapCallIdToString(CallId callId) {
        return callId == null ? null : callId.toString();
    }

    default String mapUuidToString(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    default String mapPnrToString(Pnr pnr) {
        return pnr == null ? null : pnr.value();
    }

    default String mapFlightNumberToString(FlightNumber flightNumber) {
        return flightNumber == null ? null : flightNumber.value();
    }

    default String mapOptionalCallIdToString(Optional<CallId> activeCallId) {
        return activeCallId == null ? null : activeCallId.map(CallId::toString).orElse(null);
    }

    default Set<String> mapSkillsToStrings(Set<SkillGroup> skills) {
        if (skills == null || skills.isEmpty()) {
            return Set.of();
        }
        return skills.stream().map(SkillGroup::name).collect(Collectors.toUnmodifiableSet());
    }

    default SkillGroup resolveSkillGroupOrNull(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return SkillGroup.fromCode(code);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
