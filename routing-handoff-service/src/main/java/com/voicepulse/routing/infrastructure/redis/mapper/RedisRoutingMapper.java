package com.voicepulse.routing.infrastructure.redis.mapper;

import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceHash;
import com.voicepulse.routing.infrastructure.redis.model.QueuedTicketRedisPayload;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

/**
 * Compile-time MapStruct mapper transforming domain models ({@link OperatorPresence}, {@link
 * QueuedTicketItem}) to/from Redis infrastructure DTOs with strict {@link ReportingPolicy#ERROR}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RedisRoutingMapper {

    @Mapping(target = "operatorId", source = "operatorId", qualifiedByName = "operatorIdToString")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "skills", source = "skills", qualifiedByName = "skillsToCsv")
    @Mapping(
            target = "activeCallId",
            source = "activeCallId",
            qualifiedByName = "optionalCallIdToString")
    @Mapping(
            target = "lastHeartbeatAt",
            source = "lastHeartbeatAt",
            qualifiedByName = "instantToIso")
    OperatorPresenceHash toPresenceHash(OperatorPresence presence);

    @Mapping(target = "operatorId", source = "operatorId", qualifiedByName = "stringToOperatorId")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "skills", source = "skills", qualifiedByName = "csvToSkills")
    @Mapping(
            target = "activeCallId",
            source = "activeCallId",
            qualifiedByName = "stringToOptionalCallId")
    @Mapping(
            target = "lastHeartbeatAt",
            source = "lastHeartbeatAt",
            qualifiedByName = "isoToInstant")
    @Mapping(target = "refreshHeartbeat", ignore = true)
    OperatorPresence toOperatorPresence(OperatorPresenceHash hash);

    @Mapping(target = "callId", source = "ticketEvent.callId", qualifiedByName = "callIdToString")
    @Mapping(target = "ticketId", source = "ticketEvent.ticketId", qualifiedByName = "uuidToString")
    @Mapping(target = "passengerName", source = "ticketEvent.passengerName")
    @Mapping(target = "pnr", source = "ticketEvent.pnr", qualifiedByName = "pnrToString")
    @Mapping(
            target = "originalFlightNumber",
            source = "ticketEvent.originalFlightNumber",
            qualifiedByName = "flightNumberToString")
    @Mapping(target = "disruptionReason", source = "ticketEvent.disruptionReason")
    @Mapping(target = "rebookingPreference", source = "ticketEvent.rebookingPreference")
    @Mapping(target = "hotelRequired", source = "ticketEvent.hotelRequired")
    @Mapping(target = "specialAssistance", source = "ticketEvent.specialAssistance")
    @Mapping(target = "targetSkillGroup", source = "ticketEvent.targetSkillGroup")
    @Mapping(target = "payload", source = "ticketEvent.payload")
    @Mapping(target = "eventTimestamp", source = "ticketEvent.timestamp")
    @Mapping(target = "skillGroup", source = "skillGroup")
    @Mapping(target = "priorityScore", source = "priorityScore")
    @Mapping(target = "enqueuedAt", source = "enqueuedAt")
    QueuedTicketRedisPayload toRedisPayload(QueuedTicketItem item);

    @Mapping(target = "ticketEvent", expression = "java(toTicketFormFilledEvent(payload))")
    @Mapping(target = "skillGroup", source = "skillGroup")
    @Mapping(target = "priorityScore", source = "priorityScore")
    @Mapping(target = "enqueuedAt", source = "enqueuedAt")
    QueuedTicketItem toQueuedTicketItem(QueuedTicketRedisPayload payload);

    @Mapping(target = "callId", source = "callId", qualifiedByName = "stringToCallId")
    @Mapping(target = "ticketId", source = "ticketId", qualifiedByName = "stringToUuid")
    @Mapping(target = "passengerName", source = "passengerName")
    @Mapping(target = "pnr", source = "pnr", qualifiedByName = "stringToPnr")
    @Mapping(
            target = "originalFlightNumber",
            source = "originalFlightNumber",
            qualifiedByName = "stringToFlightNumber")
    @Mapping(target = "disruptionReason", source = "disruptionReason")
    @Mapping(target = "rebookingPreference", source = "rebookingPreference")
    @Mapping(target = "hotelRequired", source = "hotelRequired")
    @Mapping(target = "specialAssistance", source = "specialAssistance")
    @Mapping(target = "targetSkillGroup", source = "targetSkillGroup")
    @Mapping(target = "payload", source = "payload")
    @Mapping(target = "timestamp", source = "eventTimestamp")
    TicketFormFilledEvent toTicketFormFilledEvent(QueuedTicketRedisPayload payload);

    default Optional<OperatorPresence> fromRedisHashEntries(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        String operatorId = entries.get("operatorId");
        String displayName = entries.get("displayName");
        String status = entries.get("status");
        String skills = entries.get("skills");
        String activeCallId = entries.getOrDefault("activeCallId", "");
        String lastHeartbeatAt = entries.get("lastHeartbeatAt");
        if (isBlank(operatorId)
                || isBlank(displayName)
                || isBlank(status)
                || isBlank(skills)
                || isBlank(lastHeartbeatAt)) {
            return Optional.empty();
        }
        OperatorPresenceHash hash =
                new OperatorPresenceHash(
                        operatorId, displayName, status, skills, activeCallId, lastHeartbeatAt);
        return Optional.of(toOperatorPresence(hash));
    }

    @Named("operatorIdToString")
    default String mapOperatorIdToString(OperatorId operatorId) {
        return operatorId == null ? null : operatorId.value();
    }

    @Named("stringToOperatorId")
    default OperatorId mapStringToOperatorId(String value) {
        return value == null ? null : OperatorId.fromString(value);
    }

    @Named("skillsToCsv")
    default String mapSkillsToCsv(Set<SkillGroup> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        return skills.stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(SkillGroup::name)
                .collect(Collectors.joining(","));
    }

    @Named("csvToSkills")
    default Set<SkillGroup> mapCsvToSkills(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(SkillGroup::fromCode)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Named("optionalCallIdToString")
    default String mapOptionalCallIdToString(Optional<CallId> activeCallId) {
        return activeCallId == null ? "" : activeCallId.map(CallId::toString).orElse("");
    }

    @Named("stringToOptionalCallId")
    default Optional<CallId> mapStringToOptionalCallId(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(CallId.fromString(value.trim()));
    }

    @Named("instantToIso")
    default String mapInstantToIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    @Named("isoToInstant")
    default Instant mapIsoToInstant(String iso) {
        return iso == null ? null : Instant.parse(iso);
    }

    @Named("callIdToString")
    default String mapCallIdToString(CallId callId) {
        return callId == null ? null : callId.toString();
    }

    @Named("stringToCallId")
    default CallId mapStringToCallId(String callId) {
        return callId == null ? null : CallId.fromString(callId);
    }

    @Named("uuidToString")
    default String mapUuidToString(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    @Named("stringToUuid")
    default UUID mapStringToUuid(String uuid) {
        return uuid == null ? null : UUID.fromString(uuid);
    }

    @Named("pnrToString")
    default String mapPnrToString(Pnr pnr) {
        return pnr == null ? null : pnr.value();
    }

    @Named("stringToPnr")
    default Pnr mapStringToPnr(String pnr) {
        return pnr == null ? null : new Pnr(pnr);
    }

    @Named("flightNumberToString")
    default String mapFlightNumberToString(FlightNumber flightNumber) {
        return flightNumber == null ? null : flightNumber.value();
    }

    @Named("stringToFlightNumber")
    default FlightNumber mapStringToFlightNumber(String flightNumber) {
        return flightNumber == null ? null : new FlightNumber(flightNumber);
    }

    default OperatorAvailabilityStatus mapStringToAvailabilityStatus(String status) {
        return status == null ? null : OperatorAvailabilityStatus.valueOf(status);
    }

    default DisruptionReason mapStringToDisruptionReason(String reason) {
        return reason == null ? null : DisruptionReason.valueOf(reason);
    }

    default RebookingPreference mapStringToRebookingPreference(String preference) {
        return preference == null ? null : RebookingPreference.valueOf(preference);
    }

    default SkillGroup mapStringToSkillGroup(String skillGroup) {
        return skillGroup == null ? null : SkillGroup.fromCode(skillGroup);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
