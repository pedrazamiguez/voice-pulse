package com.voicepulse.routing.domain.ports.outbound;

import com.voicepulse.routing.domain.model.HandoffLifecycleState;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven (outbound) SPI port for durable PostgreSQL persistence of disruption tickets and operator
 * handoff audit logs (RFC-0001 §3, {@code config/postgres/init-db.sql}).
 */
public interface TicketPersistencePort {

    /**
     * Persists a finalized disruption ticket and its initial handoff state.
     *
     * @param event ticket event payload
     * @param skillGroup resolved target skill group
     * @param initialState initial handoff lifecycle state
     */
    void saveTicket(
            TicketFormFilledEvent event, SkillGroup skillGroup, HandoffLifecycleState initialState);

    /**
     * Updates the status column of an existing persisted disruption ticket.
     *
     * @param ticketId ticket identifier
     * @param status updated ticket status
     * @param updatedAt update timestamp
     */
    void updateTicketStatus(UUID ticketId, String status, Instant updatedAt);

    /**
     * Appends an immutable state-transition audit entry to {@code operator_handoff_logs}.
     *
     * @param callId call identifier
     * @param eventType audit event classification
     * @param sourceState previous state name (nullable when initial)
     * @param targetState resulting state name
     * @param operatorId assigned operator identifier (nullable if unassigned)
     * @param details structured JSONB metadata
     * @param occurredAt event timestamp
     */
    void recordHandoffAuditLog(
            CallId callId,
            String eventType,
            String sourceState,
            String targetState,
            OperatorId operatorId,
            Map<String, Object> details,
            Instant occurredAt);

    /**
     * Looks up a persisted disruption ticket by its call identifier.
     *
     * @param callId call identifier
     * @return persisted ticket event if found
     */
    Optional<TicketFormFilledEvent> findTicketByCallId(CallId callId);
}
