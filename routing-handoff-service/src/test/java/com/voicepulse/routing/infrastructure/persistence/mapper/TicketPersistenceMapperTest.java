package com.voicepulse.routing.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.persistence.model.CallSessionRow;
import com.voicepulse.routing.infrastructure.persistence.model.DisruptionTicketRow;
import com.voicepulse.routing.infrastructure.persistence.model.OperatorHandoffLogRow;
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
    @DisplayName("TicketFormFilledEvent -> DisruptionTicketRow Mapping")
    class TicketRowMappingTests {

        @Test
        @DisplayName("Should map all fields and JSONB payload to DisruptionTicketRow")
        void shouldMapEventToTicketRow() {
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

            DisruptionTicketRow row =
                    mapper.toTicketRow(event, SkillGroup.DISRUPTION_DESK_GOLD, initialState);

            assertThat(row.ticketId()).isEqualTo(event.ticketId().toString());
            assertThat(row.callId()).isEqualTo(event.callId().value());
            assertThat(row.pnr()).isEqualTo("A8F9X2");
            assertThat(row.passengerName()).isEqualTo(event.passengerName());
            assertThat(row.originalFlightNumber()).isEqualTo("SW-104");
            assertThat(row.disruptionReason()).isEqualTo("CANCELED_WEATHER");
            assertThat(row.rebookingPreference()).isEqualTo("NEXT_AVAILABLE");
            assertThat(row.hotelRequired()).isTrue();
            assertThat(row.specialAssistance()).isFalse();
            assertThat(row.status()).isEqualTo("QUEUED");
            assertThat(row.priority()).isEqualTo("HIGH");
            assertThat(row.summary())
                    .contains("SW-104")
                    .contains("CANCELED_WEATHER")
                    .contains(event.passengerName())
                    .contains("NEXT_AVAILABLE");
            assertThat(row.payload()).containsExactlyInAnyOrderEntriesOf(payload);
            assertThat(row.createdAt()).isEqualTo(NOW);
            assertThat(row.updatedAt()).isEqualTo(NOW);
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

            DisruptionTicketRow row =
                    mapper.toTicketRow(event, SkillGroup.DISRUPTION_DESK_STANDARD, initialState);

            assertThat(row.priority()).isEqualTo("NORMAL");
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

            DisruptionTicketRow row =
                    mapper.toTicketRow(event, SkillGroup.DISRUPTION_DESK_STANDARD, initialState);

            assertThat(row.status()).isEqualTo("ASSIGNED");
            assertThat(row.priority()).isEqualTo("CRITICAL");
            assertThat(row.summary()).isEqualTo("VIP passenger requiring wheelchair and hotel");
        }
    }

    @Nested
    @DisplayName("CallSessionRow & HandoffAssignment Mapping")
    class CallSessionAndAssignmentMappingTests {

        @Test
        @DisplayName("Should map TicketFormFilledEvent and Completed state to CallSessionRow")
        void shouldMapEventAndCompletedStateToCallSessionRow() {
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

            CallSessionRow row =
                    mapper.toCallSessionRow(event, SkillGroup.DISRUPTION_DESK_ACCESSIBILITY, state);

            assertThat(row.callId()).isEqualTo(event.callId().value());
            assertThat(row.pnr()).isEqualTo("A8F9X2");
            assertThat(row.callerNumber()).isEqualTo("+34600112233");
            assertThat(row.status()).isEqualTo("COMPLETED");
            assertThat(row.startTime()).isEqualTo(NOW);
            assertThat(row.endTime()).isEqualTo(completedAt);
            assertThat(row.durationSeconds()).isNull();
            assertThat(row.assignedOperatorId()).isEqualTo("op-agent-7");
            assertThat(row.metadata())
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
        @DisplayName("Should map HandoffAssignment to CallSessionRow and OperatorHandoffLogRow")
        void shouldMapHandoffAssignmentToSessionAndAuditRows() {
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

            CallSessionRow sessionRow = mapper.toCallSessionRow(assignment);
            OperatorHandoffLogRow auditRow =
                    mapper.toAuditLogRow(assignment, "OPERATOR_ASSIGNED", "QUEUED");

            assertThat(sessionRow.callId()).isEqualTo(callId.value());
            assertThat(sessionRow.status()).isEqualTo("ASSIGNED");
            assertThat(sessionRow.assignedOperatorId()).isEqualTo("op-42");
            assertThat(sessionRow.metadata())
                    .containsEntry("ticketId", assignment.ticketId().toString())
                    .containsEntry("skillGroup", "DISRUPTION_DESK_GOLD")
                    .containsEntry("bridgeEndpoint", assignment.bridgeEndpoint());

            assertThat(auditRow.callId()).isEqualTo(callId.value());
            assertThat(auditRow.eventType()).isEqualTo("OPERATOR_ASSIGNED");
            assertThat(auditRow.sourceState()).isEqualTo("QUEUED");
            assertThat(auditRow.targetState()).isEqualTo("ASSIGNED");
            assertThat(auditRow.operatorId()).isEqualTo("op-42");
            assertThat(auditRow.details())
                    .containsEntry("ticketId", assignment.ticketId().toString())
                    .containsEntry("skillGroup", "DISRUPTION_DESK_GOLD")
                    .containsEntry("bridgeEndpoint", assignment.bridgeEndpoint());
            assertThat(auditRow.occurredAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("DisruptionTicketRow -> TicketFormFilledEvent Round-Trip Mapping")
    class RoundTripMappingTests {

        @Test
        @DisplayName("Should reconstruct TicketFormFilledEvent from DisruptionTicketRow")
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

            DisruptionTicketRow row =
                    mapper.toTicketRow(original, SkillGroup.DISRUPTION_DESK_GOLD, initialState);
            TicketFormFilledEvent reconstructed =
                    mapper.toTicketFormFilledEvent(row, original.targetSkillGroup());

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
            DisruptionTicketRow row =
                    mapper.toTicketRow(original, SkillGroup.DISRUPTION_DESK_STANDARD, initialState);

            TicketFormFilledEvent reconstructed = mapper.toTicketFormFilledEvent(row, "  ");

            assertThat(reconstructed.targetSkillGroup())
                    .isEqualTo(SkillGroup.DISRUPTION_DESK_STANDARD.name());
        }

        @Test
        @DisplayName("Should safely handle null scalar inputs")
        void shouldReturnNullForNullInputs() {
            assertThat(mapper.toTicketRow(null, null, null)).isNull();
            assertThat(mapper.toCallSessionRow(null, null, null)).isNull();
            assertThat(mapper.toCallSessionRow(null)).isNull();
            assertThat(mapper.toAuditLogRow(null, null, null)).isNull();
            assertThat(mapper.toTicketFormFilledEvent(null, null)).isNull();
            assertThat(mapper.resolveLifecycleStatus(null)).isNull();
            assertThat(mapper.extractOperatorId(null)).isNull();
            assertThat(mapper.extractEndTime(null)).isNull();
        }
    }
}
