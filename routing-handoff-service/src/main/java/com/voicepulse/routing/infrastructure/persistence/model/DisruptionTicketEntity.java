package com.voicepulse.routing.infrastructure.persistence.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC immutable entity record mapping the PostgreSQL {@code disruption_tickets} table
 * ({@code config/postgres/init-db.sql}).
 */
@Table("disruption_tickets")
public record DisruptionTicketEntity(
        @Id @Column("ticket_id") String ticketId,
        @Column("call_id") UUID callId,
        @Column("pnr") String pnr,
        @Column("passenger_name") String passengerName,
        @Column("original_flight_number") String originalFlightNumber,
        @Column("disruption_reason") String disruptionReason,
        @Column("rebooking_preference") String rebookingPreference,
        @Column("hotel_required") boolean hotelRequired,
        @Column("special_assistance") boolean specialAssistance,
        @Column("status") String status,
        @Column("priority") String priority,
        @Column("summary") String summary,
        @Column("payload") JsonbDocument payload,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt) {

    public DisruptionTicketEntity {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(pnr, "pnr must not be null");
        Objects.requireNonNull(passengerName, "passengerName must not be null");
        Objects.requireNonNull(originalFlightNumber, "originalFlightNumber must not be null");
        Objects.requireNonNull(disruptionReason, "disruptionReason must not be null");
        Objects.requireNonNull(rebookingPreference, "rebookingPreference must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(priority, "priority must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId must not be blank");
        }
        payload = (payload == null) ? JsonbDocument.empty() : payload;
    }

    /**
     * Returns an immutable copy of {@code incoming} preserving the original {@code createdAt}
     * timestamp.
     */
    public static DisruptionTicketEntity mergePreservingCreatedAt(
            DisruptionTicketEntity incoming, Instant existingCreatedAt) {
        Objects.requireNonNull(incoming, "incoming must not be null");
        Objects.requireNonNull(existingCreatedAt, "existingCreatedAt must not be null");
        return new DisruptionTicketEntity(
                incoming.ticketId(),
                incoming.callId(),
                incoming.pnr(),
                incoming.passengerName(),
                incoming.originalFlightNumber(),
                incoming.disruptionReason(),
                incoming.rebookingPreference(),
                incoming.hotelRequired(),
                incoming.specialAssistance(),
                incoming.status(),
                incoming.priority(),
                incoming.summary(),
                incoming.payload(),
                existingCreatedAt,
                incoming.updatedAt());
    }

    /** Returns an immutable copy with updated {@code status} and {@code updatedAt}. */
    public DisruptionTicketEntity withStatus(String updatedStatus, Instant updatedTimestamp) {
        Objects.requireNonNull(updatedStatus, "updatedStatus must not be null");
        Objects.requireNonNull(updatedTimestamp, "updatedTimestamp must not be null");
        if (updatedStatus.isBlank()) {
            throw new IllegalArgumentException("updatedStatus must not be blank");
        }
        return new DisruptionTicketEntity(
                ticketId,
                callId,
                pnr,
                passengerName,
                originalFlightNumber,
                disruptionReason,
                rebookingPreference,
                hotelRequired,
                specialAssistance,
                updatedStatus,
                priority,
                summary,
                payload,
                createdAt,
                updatedTimestamp);
    }
}
