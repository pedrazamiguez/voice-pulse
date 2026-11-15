package com.voicepulse.routing.infrastructure.persistence.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC immutable entity record mapping the PostgreSQL {@code operator_handoff_logs}
 * audit table ({@code config/postgres/init-db.sql}).
 */
@Table("operator_handoff_logs")
public record OperatorHandoffLogEntity(
        @Id @Column("id") Long id,
        @Column("call_id") UUID callId,
        @Column("event_type") String eventType,
        @Column("source_state") String sourceState,
        @Column("target_state") String targetState,
        @Column("operator_id") String operatorId,
        @Column("details") JsonbDocument details,
        @Column("occurred_at") Instant occurredAt) {

    public OperatorHandoffLogEntity {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        details = (details == null) ? JsonbDocument.empty() : details;
    }
}
