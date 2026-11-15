package com.voicepulse.routing.infrastructure.ws.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import com.voicepulse.routing.domain.model.HandoffAssignment;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.ws.config.OperatorConsoleProperties;
import com.voicepulse.routing.infrastructure.ws.dto.HandoffStateDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorAssignmentPayloadDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorPresenceDto;
import com.voicepulse.routing.infrastructure.ws.dto.QueueOverviewDto;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("OperatorConsoleMapper Compile-Time MapStruct Tests")
class OperatorConsoleMapperTest {

    private static final Instant NOW = Instant.parse("2026-11-15T08:30:00Z");

    private final OperatorConsoleMapper mapper = Mappers.getMapper(OperatorConsoleMapper.class);
    private final TranscriptContextExtractor extractor = new TranscriptContextExtractor();
    private final OperatorConsoleProperties consoleProperties =
            new OperatorConsoleProperties("ws://localhost:8080", Duration.ofSeconds(10), 256);

    private static TicketFormFilledEvent sampleTicket() {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::passengerName), "Carlos Vega")
                .set(field(TicketFormFilledEvent::pnr), new Pnr("K9M2P4"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-212"))
                .set(field(TicketFormFilledEvent::hotelRequired), true)
                .set(field(TicketFormFilledEvent::specialAssistance), false)
                .set(
                        field(TicketFormFilledEvent::targetSkillGroup),
                        SkillGroup.DISRUPTION_DESK_GOLD.name())
                .set(
                        field(TicketFormFilledEvent::payload),
                        Map.of(
                                "summary", "Gold passenger stranded in MAD",
                                "caller_number", "+34 611 000 111",
                                "transcript", List.of("CALLER: Need rebooking ASAP.")))
                .set(field(TicketFormFilledEvent::timestamp), NOW)
                .create();
    }

    @Test
    @DisplayName("Maps OperatorPresence domain model to OperatorPresenceDto")
    void mapsOperatorPresence() {
        CallId callId = CallId.generate();
        OperatorPresence presence =
                new OperatorPresence(
                        OperatorId.fromString("op-gold-1"),
                        "Elena Ruiz",
                        OperatorAvailabilityStatus.BUSY,
                        Set.of(
                                SkillGroup.DISRUPTION_DESK_GOLD,
                                SkillGroup.DISRUPTION_DESK_STANDARD),
                        Optional.of(callId),
                        NOW);

        OperatorPresenceDto dto = mapper.toPresenceDto(presence);

        assertThat(dto.operatorId()).isEqualTo("op-gold-1");
        assertThat(dto.displayName()).isEqualTo("Elena Ruiz");
        assertThat(dto.status()).isEqualTo("BUSY");
        assertThat(dto.skillGroups())
                .containsExactlyInAnyOrder("DISRUPTION_DESK_GOLD", "DISRUPTION_DESK_STANDARD");
        assertThat(dto.activeCallId()).isEqualTo(callId.toString());
        assertThat(dto.lastHeartbeatAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName(
            "Maps HandoffAssignment + TicketFormFilledEvent to rich OperatorAssignmentPayloadDto")
    void mapsAssignmentPayload() {
        TicketFormFilledEvent ticket = sampleTicket();
        OperatorId operatorId = OperatorId.fromString("op-gold-1");
        HandoffAssignment assignment =
                new HandoffAssignment(
                        ticket.callId(),
                        ticket.ticketId(),
                        operatorId,
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        "/ws/operators/op-gold-1/calls/" + ticket.callId() + "/audio",
                        NOW);

        OperatorAssignmentPayloadDto payload =
                mapper.toAssignmentPayload(assignment, ticket, consoleProperties, extractor);

        assertThat(payload.callId()).isEqualTo(ticket.callId().toString());
        assertThat(payload.ticketId()).isEqualTo(ticket.ticketId().toString());
        assertThat(payload.operatorId()).isEqualTo("op-gold-1");
        assertThat(payload.skillGroup()).isEqualTo("DISRUPTION_DESK_GOLD");
        assertThat(payload.bridgeEndpoint())
                .isEqualTo("/ws/operators/op-gold-1/calls/" + ticket.callId() + "/audio");
        assertThat(payload.bridgeUrl())
                .isEqualTo(
                        "ws://localhost:8080/ws/operators/op-gold-1/calls/"
                                + ticket.callId()
                                + "/audio");
        assertThat(payload.passengerName()).isEqualTo("Carlos Vega");
        assertThat(payload.pnr()).isEqualTo("K9M2P4");
        assertThat(payload.originalFlightNumber()).isEqualTo("SW-212");
        assertThat(payload.hotelRequired()).isTrue();
        assertThat(payload.summary()).isEqualTo("Gold passenger stranded in MAD");
        assertThat(payload.priority()).isEqualTo("HIGH");
        assertThat(payload.category()).isEqualTo("ACCOMMODATION_AND_REBOOKING");
        assertThat(payload.callerNumber()).isEqualTo("+34 611 000 111");
        assertThat(payload.transcript()).hasSize(1);
        assertThat(payload.transcript().getFirst().text()).isEqualTo("Need rebooking ASAP.");
    }

    @Test
    @DisplayName("Maps QueueOverviewDto and all sealed HandoffLifecycleState subtypes")
    void mapsQueueOverviewAndLifecycleStates() {
        TicketFormFilledEvent ticket = sampleTicket();
        QueuedTicketItem queuedItem =
                new QueuedTicketItem(ticket, SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW);

        QueueOverviewDto overview =
                mapper.toQueueOverviewDto(
                        "DISRUPTION_DESK_GOLD",
                        Map.of(
                                SkillGroup.DISRUPTION_DESK_GOLD, 2L,
                                SkillGroup.DISRUPTION_DESK_STANDARD, 1L),
                        List.of(queuedItem),
                        NOW,
                        extractor);

        assertThat(overview.selectedSkillGroup()).isEqualTo("DISRUPTION_DESK_GOLD");
        assertThat(overview.totalQueued()).isEqualTo(3L);
        assertThat(overview.depthsBySkillGroup())
                .containsEntry("DISRUPTION_DESK_GOLD", 2L)
                .containsEntry("DISRUPTION_DESK_STANDARD", 1L);
        assertThat(overview.queuedCalls()).hasSize(1);
        assertThat(overview.queuedCalls().getFirst().pnr()).isEqualTo("K9M2P4");

        CallId callId = ticket.callId();
        UUID ticketId = ticket.ticketId();
        OperatorId operatorId = OperatorId.fromString("op-1");

        HandoffStateDto queued =
                mapper.toHandoffStateDto(
                        new HandoffLifecycleState.Queued(
                                callId, ticketId, SkillGroup.DISRUPTION_DESK_GOLD, 1, NOW),
                        consoleProperties);
        assertThat(queued.status()).isEqualTo("QUEUED");
        assertThat(queued.queuePosition()).isEqualTo(1);

        HandoffStateDto assigned =
                mapper.toHandoffStateDto(
                        new HandoffLifecycleState.Assigned(
                                callId, ticketId, operatorId, SkillGroup.DISRUPTION_DESK_GOLD, NOW),
                        consoleProperties);
        assertThat(assigned.status()).isEqualTo("ASSIGNED");
        assertThat(assigned.operatorId()).isEqualTo("op-1");

        HandoffStateDto bridged =
                mapper.toHandoffStateDto(
                        new HandoffLifecycleState.AudioBridged(
                                callId,
                                ticketId,
                                operatorId,
                                "/ws/operators/op-1/calls/1/audio",
                                NOW),
                        consoleProperties);
        assertThat(bridged.status()).isEqualTo("AUDIO_BRIDGED");
        assertThat(bridged.bridgeUrl())
                .isEqualTo("ws://localhost:8080/ws/operators/op-1/calls/1/audio");

        HandoffStateDto completed =
                mapper.toHandoffStateDto(
                        new HandoffLifecycleState.Completed(
                                callId, ticketId, operatorId, "Rebooked on SW-300", NOW),
                        consoleProperties);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.resolutionNotes()).isEqualTo("Rebooked on SW-300");

        HandoffStateDto abandoned =
                mapper.toHandoffStateDto(
                        new HandoffLifecycleState.Abandoned(callId, ticketId, "CALLER_HANGUP", NOW),
                        consoleProperties);
        assertThat(abandoned.status()).isEqualTo("ABANDONED");
        assertThat(abandoned.reason()).isEqualTo("CALLER_HANGUP");
    }
}
