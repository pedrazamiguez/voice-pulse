package com.voicepulse.routing.infrastructure.ws.dto;

import java.time.Instant;

/** DTO summarizing a waiting disruption ticket inside a prioritized skill-group queue. */
public record QueuedTicketDto(
        String callId,
        String ticketId,
        String passengerName,
        String pnr,
        String originalFlightNumber,
        String disruptionReason,
        String rebookingPreference,
        boolean hotelRequired,
        boolean specialAssistance,
        String skillGroup,
        int priorityScore,
        Instant enqueuedAt,
        Instant eventTimestamp,
        String summary,
        String priority,
        String category) {}
