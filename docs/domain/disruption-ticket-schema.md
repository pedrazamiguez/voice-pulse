# Airline Disruption Ticket Schema & Data Contracts

> [!IMPORTANT]
> **Source of Truth:** Defines the structured ticket model, PostgreSQL 16 JSONB schema, and domain enums for the SkyWay Airlines Disruption scenario ([RFC-0003](../rfcs/RFC-0003-VOICEPULSE.md) & AGENTS.md §4).

---

## 1. Domain Model: `AirlineDisruptionTicket`

In accordance with Hexagonal Architecture rules, `AirlineDisruptionTicket` is an immutable Java 21 `record` residing in `com.voicepulse.conversation.domain.model` (and `shared-kernel`):

```java
package com.voicepulse.conversation.domain.model;

import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AirlineDisruptionTicket(
    UUID ticketId,
    UUID callId,
    String passengerName,
    String pnr,
    String originalFlightNumber,
    DisruptionReason disruptionReason,
    RebookingPreference rebookingPreference,
    boolean hotelRequired,
    boolean specialAssistance,
    String status,
    Map<String, Object> payload,
    Instant createdAt,
    Instant updatedAt
) {
    public AirlineDisruptionTicket {
        if (pnr == null || !pnr.matches("^[A-Za-z0-9]{6}$")) {
            throw new IllegalArgumentException("PNR must be exactly 6 alphanumeric characters: " + pnr);
        }
    }
}
```

---

## 2. PostgreSQL 16 Relational & JSONB Table Definition

The schema stores fixed queryable fields in relational columns and variable disruption details in a JSONB document indexed with GIN:

```sql
CREATE TABLE IF NOT EXISTS disruption_tickets (
    ticket_id VARCHAR(64) PRIMARY KEY,
    call_id VARCHAR(64) NOT NULL,
    passenger_name VARCHAR(128) NOT NULL,
    pnr VARCHAR(6) NOT NULL,
    original_flight_number VARCHAR(16) NOT NULL,
    disruption_reason VARCHAR(64) NOT NULL,
    rebooking_preference VARCHAR(64) NOT NULL,
    hotel_required BOOLEAN NOT NULL DEFAULT FALSE,
    special_assistance BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_HANDOFF',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_disruption_call FOREIGN KEY (call_id) 
        REFERENCES call_sessions(call_id) ON DELETE CASCADE
);

-- Primary Query Indexes
CREATE INDEX IF NOT EXISTS idx_disruption_tickets_pnr ON disruption_tickets (pnr);
CREATE INDEX IF NOT EXISTS idx_disruption_tickets_call_id ON disruption_tickets (call_id);
CREATE INDEX IF NOT EXISTS idx_disruption_tickets_status ON disruption_tickets (status);

-- GIN Index for arbitrary entity queries inside JSONB payload
CREATE INDEX IF NOT EXISTS idx_disruption_tickets_payload ON disruption_tickets USING gin (payload);
```

---

## 3. Disruption Enums

### `DisruptionReason`
```java
package com.voicepulse.shared.model;

public enum DisruptionReason {
    CANCELED_WEATHER,
    DELAYED_MAINTENANCE,
    AIR_TRAFFIC_CONTROL,
    CREW_DUTY_LIMIT,
    AIRCRAFT_AOG,
    SECURITY_INCIDENT,
    UNKNOWN
}
```

### `RebookingPreference`
```java
package com.voicepulse.shared.model;

public enum RebookingPreference {
    NEXT_AVAILABLE,
    REFUND_REQUESTED,
    SAME_CARRIER_TOMORROW,
    ALT_DESTINATION_AIRPORT,
    HOLD_TICKET_OPEN
}
```

---

## 4. Example Synthesized MCP Ticket JSON Payload

```json
{
  "ticketId": "TKT-SW-99214-X",
  "callId": "c9a4d812-78d1-47fa-bf87-9bc40efb8911",
  "passengerName": "Andrés Pedraza Míguez",
  "pnr": "A8F9X2",
  "originalFlightNumber": "SW-104",
  "disruptionReason": "CANCELED_WEATHER",
  "rebookingPreference": "NEXT_AVAILABLE",
  "hotelRequired": true,
  "specialAssistance": false,
  "status": "PENDING_HANDOFF",
  "payload": {
    "departure_airport": "CTU",
    "arrival_airport": "MAD",
    "party_size": 2,
    "hotel_rooms": 1,
    "dietary_restrictions": "None",
    "baggage_checked": true,
    "baggage_claim_tag": "SW-BG-4921",
    "preferred_departure_window": "MORNING"
  },
  "createdAt": "2026-11-07T22:30:00Z",
  "updatedAt": "2026-11-07T22:30:00Z"
}
```
