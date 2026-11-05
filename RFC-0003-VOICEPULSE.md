# VoicePulse Business Scenario: International Airline & Travel Disruption Hotline

## 1. Executive Summary
This document outlines the primary business scenario for the VoicePulse event-driven voice orchestration platform. By modeling an **International Airline & Travel Disruption Hotline**, the project demonstrates its capacity to handle massive concurrency (the "thundering herd" problem), execute stateful conversational triage, and seamlessly bridge AI-driven data collection with human operator handoffs.

The airline industry provides the perfect backdrop for VoicePulse's architectural strengths: strict latency requirements, complex alphanumeric data validation, and the necessity for immediate reactive backpressure during system-wide traffic spikes.

---

## 2. The Business Problem: The "Thundering Herd"
Airline customer service centers are uniquely vulnerable to sudden, localized traffic spikes. A single event—such as an Air Traffic Control (ATC) strike, a severe winter storm, or a global IT outage—can ground hundreds of flights simultaneously. 

Within minutes, 10,000+ stranded passengers will attempt to call the hotline. Traditional PSTN (Public Switched Telephone Network) and human-only call centers collapse under this load, leading to dropped calls and hours-long wait times. 

**VoicePulse solves this by:**
1. Ingesting the massive influx of calls via lightweight WebSockets at the Edge Gateway (Spring WebFlux / Netty).
2. Using the AI agent (Tier-1 Triage) to immediately answer, calm the passenger, and collect their identity and incident details.
3. Structuring the unstructured voice data into a prioritized JSONB ticket.
4. Placing the fully prepped ticket and call session into a routed Redis queue for the next available human agent.

---

## 3. Conversational State Machine Mapping
The conversational flow strictly maps to the VoicePulse Hexagonal Domain's Finite State Machine (FSM). The AI is bound by the rules in `AGENTS.md`: keep responses under 25 words, verify alphanumeric strings acoustically, and restrict the scope.

### `CONNECTED` -> `GREETING`
*   **System (TTS):** "Welcome to the Airline Disruption Hotline. To quickly assist you, please state your six-character booking reference or PNR."

### `COLLECTING_DATA` -> `CONFIRMING`
*   **User (Audio):** "Uh, yes, it's alpha eight, foxtrot nine, x-ray two."
*   **System (VAD & STT):** Detects silence, transcribes `A8F9X2`.
*   **System (TTS):** "I heard A 8 F 9 X 2. Is that correct?" (Acoustic verification rule enforced).
*   **User:** "Yes."

### Contextual branching via `mcp-crm-server`
Once the PNR is confirmed, the AI agent calls `lookup_customer(national_id: "A8F9X2")`. 
*   **System (TTS):** "I see you were scheduled on the direct flight from Chengdu to Madrid. Are you calling to rebook your flight, or do you need assistance with baggage or hotel accommodations?"

### Barge-in & Reactive Interruption (The Netty/VAD Advantage)
Stressed travelers often interrupt. 
*   **System (TTS):** "I can help you rebook. I see an available flight departing tomorrow at..."
*   **User (Barge-in):** "No, wait! I need a hotel for tonight first!"
*   **Platform Action:** The Conversation Engine detects the energy spike via VAD, emits an `InterruptPlaybackEvent` to Kafka, which triggers the Netty gateway to instantly flush the WebSocket audio buffer. The agent updates the context and pivots to hotel accommodations.

### `HANDOFF_READY` -> `TRANSFERRED`
*   **System (TTS):** "I have prepared your hotel request for Chengdu. I am transferring you to our priority disruption team now."
*   **Platform Action:** The agent calls the `finalize_ticket` MCP tool and transitions the call to the routing service.

---

## 4. MCP Tool Integrations & Schemas

The AI agent interacts with three deterministic MCP (Model Context Protocol) servers to build the payload for the human operator.

### 1. Identity & Context: `mcp-crm-server`
Validates the traveler's existence and retrieves active itineraries.
```json
// Tool Call: lookup_customer(pnr: "A8F9X2")
// Response:
{
  "customer_name": "Andres",
  "frequent_flyer_tier": "Gold",
  "active_itinerary": {
    "origin": "CTU",
    "destination": "MAD",
    "status": "CANCELLED_WEATHER",
    "original_departure": "2026-11-01T01:45:00Z"
  }
}
```

### 2. Triage & Compilation: `mcp-ticket-server`
Compiles the conversational intent into a structured JSON ticket, eventually stored in PostgreSQL.
```json
// Tool Call: finalize_ticket(...)
// Payload:
{
  "ticket_id": "TKT-9921-X",
  "pnr": "A8F9X2",
  "category": "HOTEL_ACCOMMODATION",
  "priority": "HIGH",
  "summary": "Flight from CTU to MAD cancelled. Passenger requires overnight hotel accommodation near Chengdu Tianfu airport before rebooking.",
  "extracted_entities": {
    "party_size": 2,
    "requires_accessibility": false
  }
}
```

### 3. Queue Assignment: `mcp-telephony-routing`
Checks operator skill groups and dispatches the call.
```json
// Tool Call: transfer_to_human_operator(call_id: "c9a4...", department: "DISRUPTION_DESK_GOLD")
// Response:
{
  "status": "QUEUED",
  "estimated_wait_seconds": 120,
  "queue_position": 14
}
```

---

## 5. Why this Scenario Shines for the Showcase

1. **Complex Payload Handling:** It proves the PostgreSQL JSONB persistence strategy. Flight itineraries and disruption tickets are highly nested and variable.
2. **Real-world Latency Constraints:** In a disruption scenario, latency matters. It gives a solid business reason to justify the Netty WebFlux architecture and the sub-300ms time-to-first-audio mandate.
3. **Justified Concurrency:** It perfectly explains the Gatling/k6 load tests (simulating 10,000 users at once) because weather disruptions cause immediate, massive call volume spikes.
4. **Relatable Agent Persona:** Everyone understands the frustration of airline customer service. A fast, competent AI that perfectly captures your PNR and issue *before* putting you on hold is a universally understood value proposition.
