package com.voicepulse.routing.infrastructure.redis.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceHash;
import com.voicepulse.routing.infrastructure.redis.model.QueuedTicketRedisPayload;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;

@DisplayName("RedisRoutingMapper Tests")
class RedisRoutingMapperTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:30:00Z");

    private final RedisRoutingMapper mapper = Mappers.getMapper(RedisRoutingMapper.class);

    @ParameterizedTest
    @EnumSource(OperatorAvailabilityStatus.class)
    @DisplayName("Should round-trip OperatorPresence across all availability statuses")
    void shouldRoundTripOperatorPresence(OperatorAvailabilityStatus status) {
        Optional<CallId> activeCall =
                status == OperatorAvailabilityStatus.BUSY
                        ? Optional.of(CallId.generate())
                        : Optional.empty();
        OperatorPresence presence =
                new OperatorPresence(
                        OperatorId.fromString("op-mapper-1"),
                        "Elena Navarro",
                        status,
                        Set.of(
                                SkillGroup.DISRUPTION_DESK_GOLD,
                                SkillGroup.DISRUPTION_DESK_PRIORITY),
                        activeCall,
                        NOW);

        OperatorPresenceHash hash = mapper.toPresenceHash(presence);
        assertThat(hash.skills()).isEqualTo("DISRUPTION_DESK_GOLD,DISRUPTION_DESK_PRIORITY");

        OperatorPresence reconstructed = mapper.toOperatorPresence(hash);
        assertThat(reconstructed).isEqualTo(presence);
    }

    @Test
    @DisplayName("Should reconstruct OperatorPresence from Redis hash entry map")
    void shouldMapFromRedisHashEntries() {
        CallId callId = CallId.generate();
        Map<String, String> fields =
                Map.of(
                        "operatorId", "op-hash-1",
                        "displayName", "Carlos Diaz",
                        "status", "BUSY",
                        "skills", "DISRUPTION_DESK_ACCESSIBILITY,DISRUPTION_DESK_STANDARD",
                        "activeCallId", callId.toString(),
                        "lastHeartbeatAt", NOW.toString());

        Optional<OperatorPresence> result = mapper.fromRedisHashEntries(fields);

        assertThat(result)
                .contains(
                        new OperatorPresence(
                                OperatorId.fromString("op-hash-1"),
                                "Carlos Diaz",
                                OperatorAvailabilityStatus.BUSY,
                                Set.of(
                                        SkillGroup.DISRUPTION_DESK_ACCESSIBILITY,
                                        SkillGroup.DISRUPTION_DESK_STANDARD),
                                Optional.of(callId),
                                NOW));
    }

    @Test
    @DisplayName("Should return Optional.empty when Redis hash entries are empty or incomplete")
    void shouldReturnEmptyWhenHashEntriesIncomplete() {
        assertThat(mapper.fromRedisHashEntries(null)).isEmpty();
        assertThat(mapper.fromRedisHashEntries(Map.of())).isEmpty();
        assertThat(mapper.fromRedisHashEntries(Map.of("operatorId", "op-1"))).isEmpty();
    }

    @Test
    @DisplayName("Should round-trip QueuedTicketItem to and from QueuedTicketRedisPayload")
    void shouldRoundTripQueuedTicketItem() {
        TicketFormFilledEvent event =
                Instancio.of(TicketFormFilledEvent.class)
                        .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                        .set(
                                field(TicketFormFilledEvent::originalFlightNumber),
                                new FlightNumber("SW-104"))
                        .set(field(TicketFormFilledEvent::targetSkillGroup), "DISRUPTION_DESK_GOLD")
                        .set(
                                field(TicketFormFilledEvent::payload),
                                Map.of("hotel_rooms", 1, "baggage_tag", "SW-112"))
                        .set(field(TicketFormFilledEvent::timestamp), NOW)
                        .create();
        QueuedTicketItem item =
                new QueuedTicketItem(
                        event, SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW.plusSeconds(3));

        QueuedTicketRedisPayload redisPayload = mapper.toRedisPayload(item);
        QueuedTicketItem reconstructed = mapper.toQueuedTicketItem(redisPayload);

        assertThat(reconstructed).isEqualTo(item);
    }
}
