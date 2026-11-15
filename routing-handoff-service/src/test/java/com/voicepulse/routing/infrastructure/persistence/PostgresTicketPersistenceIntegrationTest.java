package com.voicepulse.routing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import com.voicepulse.routing.RoutingHandoffApplication;
import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL 16 Testcontainers integration test verifying {@link JdbcTicketPersistenceAdapter}
 * against {@code config/postgres/init-db.sql} (RFC-0001 §3, Epic #140 Phase 2).
 */
@SpringBootTest(
        classes = RoutingHandoffApplication.class,
        properties = {
            "spring.main.web-application-type=none",
            "voicepulse.routing.persistence.enabled=true",
            "voicepulse.routing.redis.enabled=false",
            "voicepulse.routing.kafka.enabled=false",
            "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration"
        })
@Testcontainers
@DirtiesContext
@DisplayName("PostgreSQL 16 Ticket & Audit Persistence Integration Tests")
class PostgresTicketPersistenceIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-14T10:15:30Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("voicepulse")
                    .withUsername("voicepulse")
                    .withPassword("voicepulse")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(resolveInitDbSqlPath()),
                            "/docker-entrypoint-initdb.d/init-db.sql");

    private final TicketPersistencePort persistencePort;
    private final JdbcTemplate jdbcTemplate;

    PostgresTicketPersistenceIntegrationTest(
            @Autowired TicketPersistencePort persistencePort,
            @Autowired JdbcTemplate jdbcTemplate) {
        this.persistencePort = persistencePort;
        this.jdbcTemplate = jdbcTemplate;
    }

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    private static Path resolveInitDbSqlPath() {
        Path direct = Path.of("config/postgres/init-db.sql");
        if (Files.exists(direct)) {
            return direct;
        }
        Path parent = Path.of("../config/postgres/init-db.sql");
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalStateException("Could not locate config/postgres/init-db.sql");
    }

    private static TicketFormFilledEvent sampleEvent(
            CallId callId, Map<String, Object> payload, Instant timestamp) {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::callId), callId)
                .set(field(TicketFormFilledEvent::passengerName), "Andres Pedraza")
                .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(
                        field(TicketFormFilledEvent::disruptionReason),
                        DisruptionReason.CANCELED_WEATHER)
                .set(
                        field(TicketFormFilledEvent::rebookingPreference),
                        RebookingPreference.NEXT_AVAILABLE)
                .set(field(TicketFormFilledEvent::hotelRequired), true)
                .set(field(TicketFormFilledEvent::specialAssistance), false)
                .set(field(TicketFormFilledEvent::targetSkillGroup), "DISRUPTION_DESK_GOLD")
                .set(field(TicketFormFilledEvent::payload), payload)
                .set(field(TicketFormFilledEvent::timestamp), timestamp)
                .create();
    }

    @Test
    @DisplayName("Should wire JdbcTicketPersistenceAdapter over NoOp fallback")
    void shouldWireJdbcPersistenceAdapter() {
        assertThat(persistencePort).isInstanceOf(JdbcTicketPersistenceAdapter.class);
    }

    @Test
    @DisplayName("Should persist call_sessions, disruption_tickets with JSONB, and round-trip")
    void shouldPersistAndRoundTripTicketWithJsonbPayload() {
        CallId callId = CallId.generate();
        Map<String, Object> payload =
                Map.of(
                        "departure_airport", "CTU",
                        "arrival_airport", "MAD",
                        "party_size", 2,
                        "hotel_rooms", 1,
                        "baggage_claim_tag", "SW-BG-4921");
        TicketFormFilledEvent event = sampleEvent(callId, payload, CREATED_AT);
        HandoffLifecycleState queued =
                new HandoffLifecycleState.Queued(
                        callId, event.ticketId(), SkillGroup.DISRUPTION_DESK_GOLD, 1, CREATED_AT);

        persistencePort.saveTicket(event, SkillGroup.DISRUPTION_DESK_GOLD, queued);

        Optional<TicketFormFilledEvent> found = persistencePort.findTicketByCallId(callId);
        assertThat(found).contains(event);

        String baggageTag =
                jdbcTemplate.queryForObject(
                        "SELECT payload ->> 'baggage_claim_tag' FROM disruption_tickets WHERE"
                                + " ticket_id = ?",
                        String.class,
                        event.ticketId().toString());
        assertThat(baggageTag).isEqualTo("SW-BG-4921");

        Integer ginMatchCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM disruption_tickets WHERE payload @> ?::jsonb AND"
                                + " ticket_id = ?",
                        Integer.class,
                        "{\"hotel_rooms\": 1}",
                        event.ticketId().toString());
        assertThat(ginMatchCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Should perform idempotent upsert on duplicate ticket_id and update status")
    void shouldPerformIdempotentUpsertAndStatusUpdate() {
        CallId callId = CallId.generate();
        TicketFormFilledEvent firstEvent = sampleEvent(callId, Map.of("attempt", 1), CREATED_AT);
        HandoffLifecycleState queued =
                new HandoffLifecycleState.Queued(
                        callId,
                        firstEvent.ticketId(),
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        1,
                        CREATED_AT);

        persistencePort.saveTicket(firstEvent, SkillGroup.DISRUPTION_DESK_GOLD, queued);

        Instant retryAt = CREATED_AT.plusSeconds(10);
        TicketFormFilledEvent duplicateDelivery =
                new TicketFormFilledEvent(
                        callId,
                        firstEvent.ticketId(),
                        "Andres Pedraza Miguez",
                        firstEvent.pnr(),
                        firstEvent.originalFlightNumber(),
                        firstEvent.disruptionReason(),
                        RebookingPreference.REFUND_REQUESTED,
                        false,
                        true,
                        firstEvent.targetSkillGroup(),
                        Map.of("attempt", 2),
                        retryAt);
        HandoffLifecycleState assigned =
                new HandoffLifecycleState.Assigned(
                        callId,
                        firstEvent.ticketId(),
                        OperatorId.fromString("op-senior-1"),
                        SkillGroup.DISRUPTION_DESK_GOLD,
                        retryAt);

        persistencePort.saveTicket(duplicateDelivery, SkillGroup.DISRUPTION_DESK_GOLD, assigned);

        Integer ticketCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM disruption_tickets WHERE ticket_id = ?",
                        Integer.class,
                        firstEvent.ticketId().toString());
        assertThat(ticketCount).isEqualTo(1);

        String storedName =
                jdbcTemplate.queryForObject(
                        "SELECT passenger_name FROM disruption_tickets WHERE ticket_id = ?",
                        String.class,
                        firstEvent.ticketId().toString());
        assertThat(storedName).isEqualTo("Andres Pedraza Miguez");

        Instant completedAt = retryAt.plusSeconds(45);
        persistencePort.updateTicketStatus(firstEvent.ticketId(), "COMPLETED", completedAt);

        String storedStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM disruption_tickets WHERE ticket_id = ?",
                        String.class,
                        firstEvent.ticketId().toString());
        assertThat(storedStatus).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("Should record ordered operator_handoff_logs audit entries with JSONB details")
    void shouldRecordOperatorHandoffAuditLogs() {
        CallId callId = CallId.generate();
        OperatorId operatorId = OperatorId.fromString("op-audit-9");
        Instant t1 = CREATED_AT;
        Instant t2 = CREATED_AT.plusSeconds(15);

        persistencePort.recordHandoffAuditLog(
                callId,
                "TICKET_QUEUED",
                null,
                "QUEUED",
                null,
                Map.of("queuePosition", 1, "skillGroup", "DISRUPTION_DESK_GOLD"),
                t1);
        persistencePort.recordHandoffAuditLog(
                callId,
                "OPERATOR_ASSIGNED",
                "QUEUED",
                "ASSIGNED",
                operatorId,
                Map.of("bridgeEndpoint", "/ws/operators/op-audit-9/calls/" + callId + "/audio"),
                t2);

        List<Map<String, Object>> logs =
                jdbcTemplate.queryForList(
                        "SELECT event_type, source_state, target_state, operator_id,"
                                + " details ->> 'skillGroup' AS skill_group FROM"
                                + " operator_handoff_logs WHERE call_id = ? ORDER BY id ASC",
                        callId.value());

        assertThat(logs).hasSize(2);
        assertThat(logs.get(0))
                .containsEntry("event_type", "TICKET_QUEUED")
                .containsEntry("source_state", null)
                .containsEntry("target_state", "QUEUED")
                .containsEntry("operator_id", null)
                .containsEntry("skill_group", "DISRUPTION_DESK_GOLD");
        assertThat(logs.get(1))
                .containsEntry("event_type", "OPERATOR_ASSIGNED")
                .containsEntry("source_state", "QUEUED")
                .containsEntry("target_state", "ASSIGNED")
                .containsEntry("operator_id", "op-audit-9");

        String sessionOperator =
                jdbcTemplate.queryForObject(
                        "SELECT assigned_operator_id FROM call_sessions WHERE call_id = ?",
                        String.class,
                        callId.value());
        assertThat(sessionOperator).isEqualTo("op-audit-9");
    }
}
