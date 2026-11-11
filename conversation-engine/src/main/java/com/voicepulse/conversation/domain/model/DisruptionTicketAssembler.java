package com.voicepulse.conversation.domain.model;

import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pure domain factory and assembler compiling validated caller input and flight disruption
 * telemetry into immutable {@link AirlineDisruptionTicket} domain records per RFC-0003 and
 * AGENTS.md §4.
 *
 * <p>Enforces strict domain invariants (6-character PNR validation, non-null fields, immutable
 * metadata payloads) with zero framework contamination.
 */
public final class DisruptionTicketAssembler {

    private static final Pattern PNR_PATTERN = Pattern.compile("^[A-Za-z0-9]{6}$");
    private static final String DEFAULT_STATUS = "COMPILED";

    private DisruptionTicketAssembler() {
        // Pure domain utility
    }

    /**
     * Compiles validated caller input into an immutable {@link AirlineDisruptionTicket}.
     *
     * @param callId unique call identifier
     * @param passengerName verified passenger name
     * @param pnr verified 6-character PNR
     * @param originalFlightNumber disrupted flight number
     * @param disruptionReason disruption root cause
     * @param rebookingPreference passenger rebooking preference
     * @param hotelRequired true if overnight accommodation is needed
     * @param specialAssistance true if wheelchair or accessibility assistance is required
     * @param timestamp timestamp of ticket assembly
     * @return fully assembled and validated disruption ticket
     */
    public static AirlineDisruptionTicket assemble(
            UUID callId,
            String passengerName,
            String pnr,
            String originalFlightNumber,
            DisruptionReason disruptionReason,
            RebookingPreference rebookingPreference,
            boolean hotelRequired,
            boolean specialAssistance,
            Instant timestamp) {
        return assembleWithPayload(
                callId,
                passengerName,
                pnr,
                originalFlightNumber,
                disruptionReason,
                rebookingPreference,
                hotelRequired,
                specialAssistance,
                Map.of(),
                timestamp);
    }

    /**
     * Compiles validated caller input and custom metadata into an immutable {@link
     * AirlineDisruptionTicket}.
     *
     * @param callId unique call identifier
     * @param passengerName verified passenger name
     * @param pnr verified 6-character PNR
     * @param originalFlightNumber disrupted flight number
     * @param disruptionReason disruption root cause
     * @param rebookingPreference passenger rebooking preference
     * @param hotelRequired true if overnight accommodation is needed
     * @param specialAssistance true if wheelchair or accessibility assistance is required
     * @param customPayload additional contextual metadata
     * @param timestamp timestamp of ticket assembly
     * @return fully assembled and validated disruption ticket
     */
    public static AirlineDisruptionTicket assembleWithPayload(
            UUID callId,
            String passengerName,
            String pnr,
            String originalFlightNumber,
            DisruptionReason disruptionReason,
            RebookingPreference rebookingPreference,
            boolean hotelRequired,
            boolean specialAssistance,
            Map<String, Object> customPayload,
            Instant timestamp) {

        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(passengerName, "passengerName cannot be null");
        Objects.requireNonNull(pnr, "pnr cannot be null");
        Objects.requireNonNull(originalFlightNumber, "originalFlightNumber cannot be null");
        Objects.requireNonNull(disruptionReason, "disruptionReason cannot be null");
        Objects.requireNonNull(rebookingPreference, "rebookingPreference cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        if (!PNR_PATTERN.matcher(pnr).matches()) {
            throw new IllegalArgumentException(
                    "PNR must be exactly 6 alphanumeric characters: " + pnr);
        }

        UUID ticketId = UUID.randomUUID();
        String category = TicketCategory.resolve(hotelRequired, rebookingPreference).name();

        Map<String, Object> payload = new HashMap<>();
        if (customPayload != null) {
            payload.putAll(customPayload);
        }
        payload.put("category", category);
        payload.put("priority", TicketPriority.HIGH.name());
        payload.put("disruptionReason", disruptionReason.name());
        payload.put("rebookingPreference", rebookingPreference.name());

        return new AirlineDisruptionTicket(
                ticketId,
                callId,
                passengerName,
                pnr,
                originalFlightNumber,
                disruptionReason,
                rebookingPreference,
                hotelRequired,
                specialAssistance,
                DEFAULT_STATUS,
                Map.copyOf(payload),
                timestamp,
                timestamp);
    }
}
