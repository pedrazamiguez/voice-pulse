package com.voicepulse.routing.infrastructure.persistence.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC immutable entity record mapping the PostgreSQL {@code call_sessions} table
 * ({@code config/postgres/init-db.sql}).
 */
@Table("call_sessions")
public record CallSessionEntity(
        @Id @Column("call_id") UUID callId,
        @Column("pnr") String pnr,
        @Column("caller_number") String callerNumber,
        @Column("status") String status,
        @Column("start_time") Instant startTime,
        @Column("end_time") Instant endTime,
        @Column("duration_seconds") Integer durationSeconds,
        @Column("assigned_operator_id") String assignedOperatorId,
        @Column("metadata") JsonbDocument metadata,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt) {

    public CallSessionEntity {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        metadata = (metadata == null) ? JsonbDocument.empty() : metadata;
    }

    /**
     * Merges an incoming ticket-save session snapshot onto {@code existing}, preserving existing
     * non-null attributes, {@code startTime}, {@code createdAt}, and merged {@code metadata} keys.
     */
    public static CallSessionEntity mergeForTicketSave(
            CallSessionEntity existing, CallSessionEntity incoming) {
        Objects.requireNonNull(existing, "existing must not be null");
        Objects.requireNonNull(incoming, "incoming must not be null");
        return new CallSessionEntity(
                existing.callId(),
                incoming.pnr() != null ? incoming.pnr() : existing.pnr(),
                incoming.callerNumber() != null ? incoming.callerNumber() : existing.callerNumber(),
                incoming.status(),
                existing.startTime(),
                incoming.endTime() != null ? incoming.endTime() : existing.endTime(),
                incoming.durationSeconds() != null
                        ? incoming.durationSeconds()
                        : existing.durationSeconds(),
                incoming.assignedOperatorId() != null
                        ? incoming.assignedOperatorId()
                        : existing.assignedOperatorId(),
                existing.metadata().mergeWith(incoming.metadata()),
                existing.createdAt(),
                incoming.updatedAt());
    }

    /**
     * Updates session status, optional operator assignment, and {@code updatedAt} during audit
     * logging while preserving {@code pnr}, {@code callerNumber}, {@code metadata}, and {@code
     * createdAt}.
     */
    public CallSessionEntity mergeForAudit(
            String targetStatus, String operatorId, Instant occurredAt) {
        Objects.requireNonNull(targetStatus, "targetStatus must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (targetStatus.isBlank()) {
            throw new IllegalArgumentException("targetStatus must not be blank");
        }
        return new CallSessionEntity(
                callId,
                pnr,
                callerNumber,
                targetStatus,
                startTime,
                endTime,
                durationSeconds,
                operatorId != null ? operatorId : assignedOperatorId,
                metadata,
                createdAt,
                occurredAt);
    }

    /**
     * Creates an initial {@code call_sessions} row when an audit event precedes ticket creation.
     */
    public static CallSessionEntity initialForAudit(
            UUID callId, String status, String operatorId, Instant occurredAt) {
        return new CallSessionEntity(
                callId,
                null,
                null,
                status,
                occurredAt,
                null,
                null,
                operatorId,
                JsonbDocument.empty(),
                occurredAt,
                occurredAt);
    }
}
