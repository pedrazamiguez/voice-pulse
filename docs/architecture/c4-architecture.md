# C4 System Architecture & Container Diagrams

> [!IMPORTANT]
> **Source of Truth:** Defines C4 Level 1 (System Context) and C4 Level 2 (Container Architecture) for VoicePulse.

---

## 1. C4 Level 1: System Context Diagram

The VoicePulse platform ingests real-time bidirectional audio streams, conducts automated triage using conversational AI, and hands off structured tickets and bridged audio to human customer support operators.

```mermaid
flowchart TD
    Caller["Stranded Passenger<br/>(Caller via Browser Softphone)"]
    Operator["Human Support Agent<br/>(Operator Console UI)"]
    
    subgraph VP ["VoicePulse Telephony Platform"]
        Platform["VoicePulse Platform Core<br/>(Reactive Voice Gateway, Conversation Engine, Routing Service)"]
    end
    
    CRM["SkyWay Airlines CRM<br/>(Passenger Records & Flight Status)"]
    Postgres[("PostgreSQL 16<br/>(Tickets & Audit Logs)")]
    RedisCache[("Redis 7<br/>(Operator Presence & State)")]

    Caller -->|"Audio Streams & Interruption (WS / PCM)"| Platform
    Platform -->|"TTS Audio & Prompts (WS / PCM)"| Caller
    Platform -->|"Dispatched Ticket Data & Bridged Call (WS)"| Operator
    Operator -->|"Presence & Accept Handoff"| Platform
    Platform -->|"PNR Lookup via MCP"| CRM
    Platform -->|"Store Tickets & Sessions"| Postgres
    Platform -->|"Presence & Session State"| RedisCache
```

---

## 2. C4 Level 2: Container Diagram

```mermaid
flowchart TD
    subgraph Clients ["Client Applications"]
        BrowserPhone["Browser Softphone<br/>(WebRTC / AudioWorkletNode)"]
        OperatorUI["Operator Console<br/>(WebSocket Client)"]
    end

    subgraph Gateway ["Edge Layer"]
        Edge["edge-gateway<br/>(Java 21 / Spring WebFlux Netty)<br/>Port: 8080"]
    end

    subgraph EventBroker ["Message Broker"]
        KafkaKRaft["Apache Kafka KRaft<br/>Topic: telephony.calls.raw<br/>Topic: telephony.calls.control<br/>Topic: telephony.tickets.intent"]
    end

    subgraph DomainCore ["Conversation Core"]
        ConvEngine["conversation-engine<br/>(Hexagonal Core + FSM)<br/>VAD, Brevity, NATO Verification"]
    end

    subgraph MCPLayer ["Tool Integrations (MCP)"]
        MCPCRM["mcp-crm-server<br/>(Customer & Flight Status)"]
        MCPTicket["mcp-ticket-server<br/>(Disruption Ticket Compiler)"]
        MCPRoute["mcp-telephony-routing<br/>(Skill Group Queues)"]
    end

    subgraph Routing ["Routing & Handoff"]
        RouteService["routing-handoff-service<br/>(Handoff Orchestrator)"]
    end

    subgraph Datastores ["Persistence & Cache"]
        PG[("PostgreSQL 16<br/>call_sessions, disruption_tickets")]
        RD[("Redis 7<br/>Operator Presence & Queue FIFO")]
    end

    BrowserPhone -->|"Binary Audio Chunks (WS)"| Edge
    Edge -->|"TTS Playback Frames (WS)"| BrowserPhone
    Edge -->|"Produce AudioChunk (Key: callId)"| KafkaKRaft
    KafkaKRaft -->|"Consume In-Order Audio"| ConvEngine
    
    ConvEngine -->|"Detect Barge-in (control event)"| KafkaKRaft
    KafkaKRaft -->|"Consume InterruptPlayback"| Edge
    
    ConvEngine -->|"Tool: lookup_customer"| MCPCRM
    ConvEngine -->|"Tool: finalize_ticket"| MCPTicket
    ConvEngine -->|"Produce TicketFormFilledEvent"| KafkaKRaft

    KafkaKRaft -->|"Consume Ticket Event"| RouteService
    RouteService -->|"Tool: assign_operator"| MCPRoute
    RouteService -->|"Check / Claim Operator"| RD
    RouteService -->|"Persist Completed Ticket"| PG
    RouteService -->|"Push Ticket & Call Session (WS)"| OperatorUI
    RouteService -->|"Signal Edge Gateway to Bridge"| Edge
```

---

## 3. Container Responsibilities & Technology Stack

| Container | Technology | Responsibilities |
| :--- | :--- | :--- |
| **`edge-gateway`** | Java 21, Spring Boot 3 WebFlux, Netty | WebSocket connection pooling, 16 kHz PCM binary framing, Netty Direct Memory safety (`ByteBuf`), low-latency audio buffer flushing. |
| **`Apache Kafka (KRaft)`** | Kafka 3.7+ (ZooKeeper-less) | Strict partitioned in-order event streaming keyed on `callId`. Audio chunk transport and control signaling. |
| **`conversation-engine`** | Java 21 Hexagonal Core | Finite State Machine, VAD energy detection, 25-word brevity validator, acoustic phonetic verification, MCP client. |
| **`routing-handoff-service`** | Java 21, Spring Boot 3 | Consumes completed disruption tickets, coordinates tiered skill routing, manages operator presence in Redis, persists to PostgreSQL. |
| **`Redis 7`** | Redis (Alpine) | In-memory operator availability states, real-time queue position counters, and active call routing cache. |
| **`PostgreSQL 16`** | PostgreSQL (Alpine) | Historical relational persistence with GIN-indexed JSONB for variable flight disruption payloads. |
