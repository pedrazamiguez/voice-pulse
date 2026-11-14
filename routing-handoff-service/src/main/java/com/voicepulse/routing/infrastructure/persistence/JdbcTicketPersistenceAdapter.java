package com.voicepulse.routing.infrastructure.persistence;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
import com.voicepulse.routing.infrastructure.persistence.mapper.TicketPersistenceMapper;
import com.voicepulse.routing.infrastructure.persistence.model.CallSessionRow;
import com.voicepulse.routing.infrastructure.persistence.model.DisruptionTicketRow;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL 16 JDBC persistence adapter implementing {@link TicketPersistencePort}.
 *
 * <p>All blocking JDBC calls execute transactionally on Java 21 Virtual Threads via {@code
 * jdbcVirtualThreadExecutor}, keeping Netty reactive event-loop threads strictly non-blocking.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "voicepulse.routing.persistence",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class JdbcTicketPersistenceAdapter implements TicketPersistencePort {

    private static final String UPSERT_CALL_SESSION_SQL =
            """
INSERT INTO call_sessions (
    call_id, pnr, caller_number, status, start_time, end_time,
    duration_seconds, assigned_operator_id, metadata, created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
ON CONFLICT (call_id) DO UPDATE SET
    pnr = COALESCE(EXCLUDED.pnr, call_sessions.pnr),
    caller_number = COALESCE(EXCLUDED.caller_number, call_sessions.caller_number),
    status = EXCLUDED.status,
    end_time = COALESCE(EXCLUDED.end_time, call_sessions.end_time),
    duration_seconds = COALESCE(EXCLUDED.duration_seconds, call_sessions.duration_seconds),
    assigned_operator_id = COALESCE(EXCLUDED.assigned_operator_id, call_sessions.assigned_operator_id),
    metadata = call_sessions.metadata || EXCLUDED.metadata,
    updated_at = EXCLUDED.updated_at
""";

    private static final String ENSURE_CALL_SESSION_FOR_AUDIT_SQL =
            """
INSERT INTO call_sessions (
    call_id, status, assigned_operator_id, start_time, created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT (call_id) DO UPDATE SET
    status = EXCLUDED.status,
    assigned_operator_id = COALESCE(EXCLUDED.assigned_operator_id, call_sessions.assigned_operator_id),
    updated_at = EXCLUDED.updated_at
""";

    private static final String UPSERT_TICKET_SQL =
            """
            INSERT INTO disruption_tickets (
                ticket_id, call_id, pnr, passenger_name, original_flight_number,
                disruption_reason, rebooking_preference, hotel_required, special_assistance,
                status, priority, summary, payload, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
            ON CONFLICT (ticket_id) DO UPDATE SET
                call_id = EXCLUDED.call_id,
                pnr = EXCLUDED.pnr,
                passenger_name = EXCLUDED.passenger_name,
                original_flight_number = EXCLUDED.original_flight_number,
                disruption_reason = EXCLUDED.disruption_reason,
                rebooking_preference = EXCLUDED.rebooking_preference,
                hotel_required = EXCLUDED.hotel_required,
                special_assistance = EXCLUDED.special_assistance,
                status = EXCLUDED.status,
                priority = EXCLUDED.priority,
                summary = EXCLUDED.summary,
                payload = EXCLUDED.payload,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String UPDATE_TICKET_STATUS_SQL =
            """
            UPDATE disruption_tickets
            SET status = ?, updated_at = ?
            WHERE ticket_id = ?
            """;

    private static final String INSERT_AUDIT_LOG_SQL =
            """
            INSERT INTO operator_handoff_logs (
                call_id, event_type, source_state, target_state, operator_id, details, occurred_at
            ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
            """;

    private static final String FIND_TICKET_BY_CALL_ID_SQL =
            """
SELECT dt.ticket_id, dt.call_id, dt.pnr, dt.passenger_name, dt.original_flight_number,
       dt.disruption_reason, dt.rebooking_preference, dt.hotel_required, dt.special_assistance,
       dt.status, dt.priority, dt.summary, dt.payload, dt.created_at, dt.updated_at,
       cs.metadata AS session_metadata
FROM disruption_tickets dt
LEFT JOIN call_sessions cs ON dt.call_id = cs.call_id
WHERE dt.call_id = ?
ORDER BY dt.created_at DESC
LIMIT 1
""";

    private final @NonNull JdbcTemplate jdbcTemplate;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull ExecutorService jdbcVirtualThreadExecutor;
    private final @NonNull TicketPersistenceMapper mapper;
    private final @NonNull JsonbColumnCodec jsonbCodec;

    @Override
    public void saveTicket(
            TicketFormFilledEvent event,
            SkillGroup skillGroup,
            HandoffLifecycleState initialState) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(skillGroup, "skillGroup must not be null");
        Objects.requireNonNull(initialState, "initialState must not be null");

        CallSessionRow sessionRow = mapper.toCallSessionRow(event, skillGroup, initialState);
        DisruptionTicketRow ticketRow = mapper.toTicketRow(event, skillGroup, initialState);

        executeOnVirtualThread(
                "saveTicket",
                () -> {
                    transactionTemplate.executeWithoutResult(
                            status -> {
                                upsertCallSession(sessionRow);
                                upsertDisruptionTicket(ticketRow);
                            });
                    log.info(
                            "Persisted disruption ticket: ticketId={}, callId={}, pnr={},"
                                    + " status={}",
                            ticketRow.ticketId(),
                            ticketRow.callId(),
                            ticketRow.pnr(),
                            ticketRow.status());
                    return null;
                });
    }

    @Override
    public void updateTicketStatus(UUID ticketId, String status, Instant updatedAt) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        String trimmedStatus = status.trim();
        if (trimmedStatus.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }

        executeOnVirtualThread(
                "updateTicketStatus",
                () -> {
                    Integer updatedRows =
                            transactionTemplate.execute(
                                    txStatus ->
                                            jdbcTemplate.update(
                                                    UPDATE_TICKET_STATUS_SQL,
                                                    trimmedStatus,
                                                    Timestamp.from(updatedAt),
                                                    ticketId.toString()));
                    log.debug(
                            "Updated disruption ticket status: ticketId={}, status={},"
                                    + " rowsUpdated={}",
                            ticketId,
                            trimmedStatus,
                            updatedRows);
                    return null;
                });
    }

    @Override
    public void recordHandoffAuditLog(
            CallId callId,
            String eventType,
            String sourceState,
            String targetState,
            OperatorId operatorId,
            Map<String, Object> details,
            Instant occurredAt) {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(targetState, "targetState must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        String trimmedEventType = eventType.trim();
        String trimmedTargetState = targetState.trim();
        if (trimmedEventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (trimmedTargetState.isBlank()) {
            throw new IllegalArgumentException("targetState must not be blank");
        }

        String normalizedOperatorId = operatorId == null ? null : operatorId.value();
        String detailsJson = jsonbCodec.toJson(details);

        executeOnVirtualThread(
                "recordHandoffAuditLog",
                () -> {
                    transactionTemplate.executeWithoutResult(
                            txStatus -> {
                                ensureCallSessionExists(
                                        callId.value(),
                                        trimmedTargetState,
                                        normalizedOperatorId,
                                        occurredAt);
                                jdbcTemplate.update(
                                        INSERT_AUDIT_LOG_SQL,
                                        callId.value(),
                                        trimmedEventType,
                                        nullableVarchar(sourceState),
                                        trimmedTargetState,
                                        nullableVarchar(normalizedOperatorId),
                                        detailsJson,
                                        Timestamp.from(occurredAt));
                            });
                    log.debug(
                            "Recorded handoff audit log: callId={}, eventType={}, source={},"
                                    + " target={}, operatorId={}",
                            callId,
                            trimmedEventType,
                            sourceState,
                            trimmedTargetState,
                            normalizedOperatorId);
                    return null;
                });
    }

    @Override
    public Optional<TicketFormFilledEvent> findTicketByCallId(CallId callId) {
        Objects.requireNonNull(callId, "callId must not be null");

        return executeOnVirtualThread(
                "findTicketByCallId",
                () -> {
                    List<TicketFormFilledEvent> rows =
                            jdbcTemplate.query(
                                    FIND_TICKET_BY_CALL_ID_SQL,
                                    this::mapTicketResultRow,
                                    callId.value());
                    return rows.stream().findFirst();
                });
    }

    private void upsertCallSession(CallSessionRow row) {
        jdbcTemplate.update(
                UPSERT_CALL_SESSION_SQL,
                row.callId(),
                nullableVarchar(row.pnr()),
                nullableVarchar(row.callerNumber()),
                row.status(),
                Timestamp.from(row.startTime()),
                nullableTimestamp(row.endTime()),
                nullableInteger(row.durationSeconds()),
                nullableVarchar(row.assignedOperatorId()),
                jsonbCodec.toJson(row.metadata()),
                Timestamp.from(row.createdAt()),
                Timestamp.from(row.updatedAt()));
    }

    private void ensureCallSessionExists(
            UUID callId, String status, String operatorId, Instant occurredAt) {
        Timestamp timestamp = Timestamp.from(occurredAt);
        jdbcTemplate.update(
                ENSURE_CALL_SESSION_FOR_AUDIT_SQL,
                callId,
                status,
                nullableVarchar(operatorId),
                timestamp,
                timestamp,
                timestamp);
    }

    private void upsertDisruptionTicket(DisruptionTicketRow row) {
        jdbcTemplate.update(
                UPSERT_TICKET_SQL,
                row.ticketId(),
                row.callId(),
                row.pnr(),
                row.passengerName(),
                row.originalFlightNumber(),
                row.disruptionReason(),
                row.rebookingPreference(),
                row.hotelRequired(),
                row.specialAssistance(),
                row.status(),
                row.priority(),
                nullableVarchar(row.summary()),
                jsonbCodec.toJson(row.payload()),
                Timestamp.from(row.createdAt()),
                Timestamp.from(row.updatedAt()));
    }

    private TicketFormFilledEvent mapTicketResultRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> payload = jsonbCodec.fromJson(rs.getString("payload"));
        Map<String, Object> sessionMetadata = jsonbCodec.fromJson(rs.getString("session_metadata"));
        String targetSkillGroup =
                sessionMetadata.get("targetSkillGroup") instanceof String skill ? skill : null;

        DisruptionTicketRow row =
                new DisruptionTicketRow(
                        rs.getString("ticket_id"),
                        rs.getObject("call_id", UUID.class),
                        rs.getString("pnr"),
                        rs.getString("passenger_name"),
                        rs.getString("original_flight_number"),
                        rs.getString("disruption_reason"),
                        rs.getString("rebooking_preference"),
                        rs.getBoolean("hotel_required"),
                        rs.getBoolean("special_assistance"),
                        rs.getString("status"),
                        rs.getString("priority"),
                        rs.getString("summary"),
                        payload,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant());

        return mapper.toTicketFormFilledEvent(row, targetSkillGroup);
    }

    private <T> T executeOnVirtualThread(String operationName, Supplier<T> action) {
        try {
            return CompletableFuture.supplyAsync(action, jdbcVirtualThreadExecutor).join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof TicketPersistenceException persistenceEx) {
                throw persistenceEx;
            }
            throw new TicketPersistenceException(
                    "Failed to execute PostgreSQL persistence operation: " + operationName,
                    cause != null ? cause : ex);
        }
    }

    private static SqlParameterValue nullableVarchar(String value) {
        return new SqlParameterValue(Types.VARCHAR, value);
    }

    private static SqlParameterValue nullableTimestamp(Instant instant) {
        return new SqlParameterValue(
                Types.TIMESTAMP, instant == null ? null : Timestamp.from(instant));
    }

    private static SqlParameterValue nullableInteger(Integer value) {
        return new SqlParameterValue(Types.INTEGER, value);
    }
}
