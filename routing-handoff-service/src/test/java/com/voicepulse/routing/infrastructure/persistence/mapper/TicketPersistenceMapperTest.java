package com.voicepulse.routing.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.persistence.model.CallSessionEntity;
import com.voicepulse.routing.infrastructure.persistence.model.DisruptionTicketEntity;
import com.voicepulse.routing.infrastructure.persistence.model.JsonbDocument;
import com.voicepulse.routing.infrastructure.persistence.model.OperatorHandoffLogEntity;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("TicketPersistenceMapper MapStruct Unit Tests")
class TicketPersistenceMapperTest {

    private static final Instant NOW = Instant.parse("2026-11-14T11:30:00Z");

    private final TicketPersistenceMapper mapper = Mappers.getMapper(TicketPersistenceMapper.class);

    private static TicketFormFilledEvent sampleEvent(
            boolean hotelRequired, boolean specialAssistance, Map<String, Object> payload) {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(
                        field(TicketFormFilledEvent::disruptionReason),
                        DisruptionReason.CANCELED_WEATHER)
                .set(
                        field(TicketFormFilledEvent::rebookingPreference),
                        RebookingPreference.NEXT_AVAILABLE)
                .set(field(TicketFormFilledEvent::hotelRequired), hotelRequired)
                .set(field(TicketFormFilledEvent::specialAssistance), specialAssistance)
                .set(field(TicketFormFilledEvent::targetSkillGroup), "DISRUPTION_DESK_GOLD")
                .set(field(TicketFormFilledEvent::payload), payload)
                .set(field(TicketFormFilledEvent::timestamp), NOW)
                .create();
    }

    @Nested
    @DisplayName("TicketFormFilledEvent -> DisruptionTicketEntity Mapping")
    class TicketEntityMappingTests {

        @Test
        @DisplayName("Should map all fields and JSONB payload to DisruptionTicketEntity")
        void shouldMapEventToTicketEntity() {
            Map<String, Object> payload =
                    Map.of(
                            "departure_airport", "CTU",
                            "arrival_airport", "MAD",
                            "party_size", 2);
            TicketFormFilledEvent event = sampleEvent(true, false, payload);
            HandoffLifecycleState initialState =
                    new HandoffLifecycleState.Queued(
                            event.callId(),
                            event.ticketId(),
                            SkillGroup.DISRUPTION_DESK_GOLD,
                            1,
                            NOW);

            DisruptionTicketEntity entity =
                    mapper.toTicketEntity(event, SkillGroup.DISRUPTION_DESK_GOLD, initialState);

            assertThat(entity.ticketId()).isEqualTo(event.ticketId().toString());
            assertThat(entity.callId()).isEqualTo(event.callId().value());
            assertThat(entity.pnr()).isEqualTo("A8F9X2");
            assertThat(entity.passengerName()).isEqualTo(event.passengerName());
            assertThat(entity.originalFlightNumber()).isEqualTo("SW-104");
            assertThat(entity.disruptionReason()).isEqualTo("CANCELED_WEATHER");
            assertThat(entity.rebookingPreference()).isEqualTo("NEXT_AVAILABLE");
            assertThat(entity.hotelRequired()).isTrue();
            assertThat(entity.specialAssistance()).isFalse();
            assertThat(entity.status()).isEqualTo("QUEUED");
            assertThat(entity.priority()).isEqualTo("HIGH");
            assertThat(entity.summary())
                    .contains("SW-104")
                    .contains("CANCELED_WEATHER")
                    .contains(event.passengerName())
                    .contains("NEXT_AVAILABLE");
            assertThat(entity.payload().entries()).containsExactlyInAnyOrderEntriesOf(payload);
            assertThat(entity.createdAt()).isEqualTo(NOW);
            assertThat(entity.updatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("Should assign NORMAL priority when standard desk and no flags")
        void shouldAssignNormalPriorityForStandardNonFlaggedTicket() {
            TicketFormFilledEvent event = sampleEvent(false, false, Map.of());
            HandoffLifecycleState initialState =
                    new HandoffLifecycleState.Queued(
                            event.callId(),
                            event.ticketId(),
                            SkillGroup.DISRUPTION_DESK_STANDARD,
                            0,
                            NOW);

            DisruptionTicketEntity entity =
                    mapper.toTicketEntity(event, SkillGroup.DISRUPTION_DESK_STANDARD, initialState);

            assertThat(entity.priority()).isEqualTo("NORMAL");
        }

        @Test
        @DisplayName("Should preserve explicit priority and summary from payload overrides")
        void shouldPreservePayloadOverrides() {
            Map<String, Object> payload =
                    Map.of(
                            "priority", "CRITICAL",
                            "summary", "VIP passenger requiring wheelchair and hotel");
            TicketFormFilledEvent event = sampleEvent(false, false, payload);
            HandoffLifecycleState initialState =
                    new HandoffLifecycleState.Assigned(
                            event.callId(),
                            event.ticketId(),
                            OperatorId.fromString("op-vip-1"),
                            SkillGroup.DISRUPTION_DESK_STANDARD,
                            NOW);

            DisruptionTicketEntity entity =
                    mapper.toTicketEntity(event, SkillGroup.DISRUPTION_DESK_STANDARD, initialState);

            assertThat(entity.status()).isEqualTo("ASSIGNED");
            assertThat(entity.priority()).isEqualTo("CRITICAL");
            assertThat(entity.summary()).isEqualTo("VIP passenger requiring wheelchair and hotel");
        }
    }

    @Nested
    @DisplayName("CallSessionEntity & HandoffAssignment Mapping")
    class CallSessionAndAssignmentMappingTests {

        @Test
        @DisplayName("Should map TicketFormFilledEvent and Completed state to CallSessionEntity")
        void shouldMapEventAndCompletedStateToCallSessionEntity() {
            Instant completedAt = NOW.plusSeconds(120);
            TicketFormFilledEvent event =
                    sampleEvent(false, true, Map.of("caller_number", "+34600112233"));
            OperatorId operatorId = OperatorId.fromString("op-agent-7");
            HandoffLifecycleState state =
                    new HandoffLifecycleState.Completed(
                            event.callId(),
                            event.ticketId(),
                            operatorId,
                            "Rebooked on SW-108",
                            completedAt);

            CallSessionEntity entity =
                    mapper.toCallSessionEntity(
                            event, SkillGroup.DISRUPTION_DESK_ACCESSIBILITY, state);

            assertThat(entity.callId()).isEqualTo(event.callId().value());
            assertThat(entity.pnr()).isEqualTo("A8F9X2");
            assertThat(entity.callerNumber()).isEqualTo("+34600112233");
            assertThat(entity.status()).isEqualTo("COMPLETED");
            assertThat(entity.startTime()).isEqualTo(NOW);
            assertThat(entity.endTime()).isEqualTo(completedAt);
            assertThat(entity.durationSeconds()).isNull();
            assertThat(entity.assignedOperatorId()).isEqualTo("op-agent-7");
            assertThat(entity.metadata().entries())
                    .containsEntry("targetSkillGroup", "DISRUPTION_DESK_GOLD")
                    .containsEntry("resolvedSkillGroup", "DISRUPTION_DESK_ACCESSIBILITY");
        }

        @Test
        @DisplayName("Should map all HandoffLifecycleState sealed variants cleanly")
        void shouldResolveAllLifecycleStates() {
            CallId callId = CallId.generate();
            UUID ticketId = UUID.randomUUID();
            OperatorId opId = OperatorId.fromString("op-1");

            assertThat(
                            mapper.resolveLifecycleStatus(
                                    new HandoffLifecycleState.Queued(
                                            callId,
                                            ticketId,
                                            SkillGroup.DISRUPTION_DESK_STANDARD,
                                            2,
                                            NOW)))
                    .isEqualTo("QUEUED");
            assertThat(
                            mapper.resolveLifecycleStatus(
                                    new HandoffLifecycleState.Assigned(
                                            callId,
                                            ticketId,
                                            opId,
                                            SkillGroup.DISRUPTION_DESK_PRIORITY,
                                            NOW)))
                    .isEqualTo("ASSIGNED");
            assertThat(
                            mapper.resolveLifecycleStatus(
                                    new HandoffLifecycleState.AudioBridged(
                                            callId,
                                            ticketId,
                                            opId,
                                            "/ws/operators/op-1/calls/" + callId + "/audio",
                                            NOW)))
                    .isEqualTo("AUDIO_BRIDGED");
            assertThat(
                            mapper.resolveLifecycleStatus(
                                    new HandoffLifecycleState.Completed(
                                            callId, ticketId, opId, "Resolved", NOW)))
                    .isEqualTo("COMPLETED");
            assertThat(
                            mapper.resolveLifecycleStatus(
                                    new HandoffLifecycleState.Abandoned(
                                            callId, ticketId, "CALLER_HANGUP", NOW)))
                    .isEqualTo("ABANDONED");
        }

        @Test
        @DisplayName(
                "Should map HandoffAssignment to CallSessionEntity and OperatorHandoffLogEntity")
        void shouldMapHandoffAssignmentToSessionAndAuditEntities() {
            CallId callId = CallId.generate();
            HandoffAssignment assignment =
                    Instancio.of(HandoffAssignment.class)
                            .set(field(HandoffAssignment::callId), callId)
                            .set(
                                    field(HandoffAssignment::operatorId),
                                    OperatorId.fromString("op-42"))
                            .set(
                                    field(HandoffAssignment::skillGroup),
                                    SkillGroup.DISRUPTION_DESK_GOLD)
                            .set(
                                    field(HandoffAssignment::bridgeEndpoint),
                                    "/ws/operators/op-42/calls/" + callId + "/audio")
                            .set(field(HandoffAssignment::assignedAt), NOW)
                            .create();

            CallSessionEntity sessionEntity = mapper.toCallSessionEntity(assignment);
            OperatorHandoffLogEntity auditEntity =
                    mapper.toAuditLogEntity(assignment, "OPERATOR_ASSIGNED", "QUEUED");

            assertThat(sessionEntity.callId()).isEqualTo(callId.value());
            assertThat(sessionEntity.status()).isEqualTo("ASSIGNED");
            assertThat(sessionEntity.assignedOperatorId()).isEqualTo("op-42");
            assertThat(sessionEntity.metadata().entries())
                    .containsEntry("ticketId", assignment.ticketId().toString())
                    .containsEntry("skillGroup", "DISRUPTION_DESK_GOLD")
                    .containsEntry("bridgeEndpoint", assignment.bridgeEndpoint());

            assertThat(auditEntity.id()).isNull();
            assertThat(auditEntity.callId()).isEqualTo(callId.value());
            assertThat(auditEntity.eventType()).isEqualTo("OPERATOR_ASSIGNED");
            assertThat(auditEntity.sourceState()).isEqualTo("QUEUED");
            assertThat(auditEntity.targetState()).isEqualTo("ASSIGNED");
            assertThat(auditEntity.operatorId()).isEqualTo("op-42");
            assertThat(auditEntity.details().entries())
                    .containsEntry("ticketId", assignment.ticketId().toString())
                    .containsEntry("skillGroup", "DISRUPTION_DESK_GOLD")
                    .containsEntry("bridgeEndpoint", assignment.bridgeEndpoint());
            assertThat(auditEntity.occurredAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("Should map raw audit parameters and JsonbDocument helper methods")
        void shouldMapAuditLogParametersAndJsonbDocumentHelpers() {
            CallId callId = CallId.generate();
            OperatorId opId = OperatorId.fromString("op-audit-3");
            OperatorHandoffLogEntity entity =
                    mapper.toAuditLogEntity(
                            callId,
                            "OPERATOR_ASSIGNED",
                            "QUEUED",
                            "ASSIGNED",
                            opId,
                            Map.of("reason", "priority-escalation"),
                            NOW);

            assertThat(entity.id()).isNull();
            assertThat(entity.callId()).isEqualTo(callId.value());
            assertThat(entity.operatorId()).isEqualTo("op-audit-3");
            assertThat(entity.details().entries()).containsEntry("reason", "priority-escalation");
            assertThat(entity.occurredAt()).isEqualTo(NOW);

            OperatorHandoffLogEntity nullDetailsEntity =
                    mapper.toAuditLogEntity(
                            callId, "TICKET_QUEUED", null, "QUEUED", null, null, NOW);
            assertThat(nullDetailsEntity.sourceState()).isNull();
            assertThat(nullDetailsEntity.details()).isEqualTo(JsonbDocument.empty());
            assertThat(nullDetailsEntity.operatorId()).isNull();
            assertThat(mapper.mapToJsonbDocument(null)).isEqualTo(JsonbDocument.empty());
            assertThat(mapper.mapJsonbDocumentToMap(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("DisruptionTicketEntity -> TicketFormFilledEvent Round-Trip Mapping")
    class RoundTripMappingTests {

        @Test
        @DisplayName("Should reconstruct TicketFormFilledEvent from DisruptionTicketEntity")
        void shouldReconstructTicketFormFilledEvent() {
            Map<String, Object> payload = Map.of("baggage_claim_tag", "SW-BG-102", "party_size", 3);
            TicketFormFilledEvent original = sampleEvent(true, true, payload);
            HandoffLifecycleState initialState =
                    new HandoffLifecycleState.Queued(
                            original.callId(),
                            original.ticketId(),
                            SkillGroup.DISRUPTION_DESK_GOLD,
                            1,
                            NOW);

            DisruptionTicketEntity entity =
                    mapper.toTicketEntity(original, SkillGroup.DISRUPTION_DESK_GOLD, initialState);
            TicketFormFilledEvent reconstructed =
                    mapper.toTicketFormFilledEvent(entity, original.targetSkillGroup());

            assertThat(reconstructed).isEqualTo(original);
        }

        @Test
        @DisplayName("Should fallback to DISRUPTION_DESK_STANDARD when targetSkillGroup is blank")
        void shouldFallbackTargetSkillGroupWhenBlank() {
            TicketFormFilledEvent original = sampleEvent(false, false, Map.of());
            HandoffLifecycleState initialState =
                    new HandoffLifecycleState.Queued(
                            original.callId(),
                            original.ticketId(),
                            SkillGroup.DISRUPTION_DESK_STANDARD,
                            0,
                            NOW);
            DisruptionTicketEntity entity =
                    mapper.toTicketEntity(
                            original, SkillGroup.DISRUPTION_DESK_STANDARD, initialState);

            TicketFormFilledEvent reconstructed = mapper.toTicketFormFilledEvent(entity, "  ");

            assertThat(reconstructed.targetSkillGroup())
                    .isEqualTo(SkillGroup.DISRUPTION_DESK_STANDARD.name());
        }

        @Test
        @DisplayName("Should safely handle null scalar inputs")
        void shouldReturnNullForNullInputs() {
            assertThat(mapper.toTicketEntity(null, null, null)).isNull();
            assertThat(mapper.toCallSessionEntity(null, null, null)).isNull();
            assertThat(mapper.toCallSessionEntity(null)).isNull();
            assertThat(mapper.toAuditLogEntity(null, null, null)).isNull();
            assertThat(mapper.toAuditLogEntity(null, null, null, null, null, null, null)).isNull();
            assertThat(mapper.toTicketFormFilledEvent(null, null)).isNull();
            assertThat(mapper.resolveLifecycleStatus(null)).isNull();
            assertThat(mapper.extractOperatorId(null)).isNull();
            assertThat(mapper.extractEndTime(null)).isNull();
        }
    }
}
