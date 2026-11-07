# VoicePulse: High-Throughput Event-Driven Voice Agent Platform

## 1. Executive Summary & Architecture Strategy

VoicePulse is a distributed, event-driven voice orchestration and automated call center platform built to handle thousands of concurrent calls without connecting to expensive public telephony (PSTN) carriers. It automates customer data capture, fills out structured support tickets via LLM-driven structured tool invocation (Function Calling), and routes prepared calls to human operators.

The core architectural philosophy is strict separation between real-time transport (audio and signaling streaming) and business-critical state management.

```
                                      +---------------------------------------------+
                                      |                Web Browser                  |
                                      |   WebRTC / WS Audio Client (Live Testing)   |
                                      +----------------------+----------------------+
                                                             |
                                                             | Audio Stream / JSON
                                                             v
+--------------------------+                      +---------------------+                      +---------------------+
|      Load Generator      |                      |   Voice Gateway     |                      |      Frontend       |
|  (Simulated WS Clients)  | --(WS Mass Traffic)->| (Reactive / Netty)  |<--(Operator WS)------|  Operator Console   |
+--------------------------+                      +----------+----------+                      |  (Next.js / Lit)    |
                                                             |                                 +----------^----------+
                                                    Kafka Ingestion                                       |
                                                    Partition Key: call_id                                |
                                                             |                                            |
                                                             v                                            |
                                                  +---------------------+                                 |
                                                  |    Apache Kafka     |                                 |
                                                  |  (KRaft Cluster)    |                                 |
                                                  +----------+----------+                                 |
                                                             |                                            |
                                                             | Raw Events / Audio Frames                  |
                                                             v                                            |
                                                  +---------------------+                                 |
                                                  | Conversation Engine |                                 |
                                                  | (Hexagonal Core)    |                                 |
                                                  | - State Machine     |                                 |
                                                  | - VAD & Interruption|                                 |
                                                  | - AI Agent / MCP    |                                 |
                                                  +----------+----------+                                 |
                                                             |                                            |
                                                             | TicketFormFilledEvent                      |
                                                             v                                            |
                                                  +---------------------+                                 |
                                                  |   Routing Service   |---------------------------------+
                                                  | (Handoff & Queues)  |      (Dispatched Ticket Data)
                                                  +----------+----------+
                                                             |
                                                             v
                                                  +---------------------+
                                                  |     PostgreSQL      |
                                                  |  (Historical Store) |
                                                  +---------------------+
```

---

## 2. Testing Without PSTN: Real Voice Interaction

To test the system with real-time bidirectional voice (speaking and listening) without paying for Twilio, Telnyx, or SIP trunk providers:

### Browser-Based WebRTC / WebSocket Audio Softphone
The project embeds a single-page interactive softphone that accesses the browser's native `navigator.mediaDevices.getUserMedia()` microphone stream:
1. **Audio Capture:** The browser captures raw audio via an `AudioWorkletNode`, downsamples it client-side to 16 kHz or 8 kHz mono Linear PCM, and streams binary frames over a secure WebSocket directly to the Voice Gateway.
2. **Streaming Ingestion:** The gateway packages these chunks into the call pipeline identically to how a CPaaS gateway (like Twilio Media Streams) forwards PSTN audio.
3. **Audio Playback:** The backend streams generated TTS audio back through the same WebSocket connection. An `AudioContext` buffer in the browser queues and plays chunks sequentially with zero browser lag.
4. **Barge-in Testing:** When you start talking while the bot is answering, the gateway's VAD triggers an interruption packet back to the browser, which immediately flushes its local playback buffer.

This allows full interactive testing with your real voice on your laptop or mobile browser, while the load generator simulates thousands of simultaneous synthetic calls in the background.

---

## 3. Technology Decisions & Rationale

| Layer | Selected Tech | Alternatives Considered | Rationale |
| :--- | :--- | :--- | :--- |
| **Edge Gateway** | Java 21 / Spring Boot 3 WebFlux (Netty) | Node.js (Fastify), Go (Gorilla WS), Python (FastAPI) | Netty provides a high-throughput event loop built for non-blocking I/O. Handling 10,000 idle or low-bandwidth WebSockets consumes minimal thread overhead and memory compared to thread-per-request models. Native memory buffer management (`ByteBuf`) handles binary audio slicing efficiently. |
| **Message Broker** | Apache Kafka (KRaft mode) | RabbitMQ, Redis Streams, AWS SQS | Strict partition-key order guarantee using `call_id`. In a conversational voice pipeline, out-of-order audio packets or state transitions cause major pipeline failures. Kafka's consumer group model allows seamless scaling of worker nodes under load. KRaft eliminates ZooKeeper dependencies in Docker. |
| **Session Cache** | Redis (Cluster / Sentinel) | Memcached, In-Memory JVM Cache | Fast lookups for active call routing, operator presence states (Available, Busy, Paused), and live WebSocket connection references. |
| **Persistence** | PostgreSQL 16 | MongoDB, DynamoDB | Relational integrity for completed tickets, audit trails, and operator metrics. Structured JSONB columns store variable form payloads without sacrificing relational joins. |
| **Metrics & Tracing** | OpenTelemetry + Prometheus + Grafana | Datadog, New Relic | Zero commercial lock-in, open standards, and native Spring Actuator metrics integration for measuring end-to-end latency and consumer lag. |

---

## 4. The 6 Architectural Pillars

### 1. Ingestion Voice Gateway (Edge Layer)
* Implemented with **Spring WebFlux on Netty**.
* Manages open WebSocket connections from the Browser Softphone and the Load Generator.
* Enforces binary packet framing and rejects oversized frames to defend against DoS attempts.
* Serializes audio chunks into structured Kafka message envelopes:
  ```json
  {
    "callId": "c9a4d812-78d1-47fa-bf87-9bc40efb8911",
    "timestamp": 1730789045120,
    "sequenceNumber": 421,
    "payloadType": "AUDIO_PCM_16KHZ",
    "data": "<base64_chunk>"
  }
  ```

### 2. Event-Driven Messaging Core (Kafka Partitioning Strategy)
* **Topic `telephony.calls.raw`:** Partitioned by `callId`. Guarantees that audio chunks and call signaling for a specific call are always consumed in strict sequential order by the exact same worker instance.
* **Topic `telephony.tickets.intent`:** High-priority topic consumed by the routing and handoff service once a ticket form is marked as complete.
* **Topic `telephony.calls.control`:** Used for signaling backpressure, cutoffs, and transfer commands back to the edge gateway.

### 3. Conversation Engine & State Machine
Built under **Hexagonal Architecture (Ports and Adapters)**:
* **Inbound Ports:** Consumes Kafka records from `telephony.calls.raw`.
* **Domain Model:** Finite State Machine (FSM) tracking call status:
  `CONNECTED` -> `GREETING` -> `COLLECTING_DATA` -> `CONFIRMING` -> `HANDOFF_READY` -> `TRANSFERRED` -> `TERMINATED`.
* **Voice Activity Detection (VAD) & Barge-in:** Evaluates frame energy and speech probability. If the customer speaks while the TTS state is active, the engine emits an immediate `InterruptPlaybackEvent` to purge down-stream audio queues.
* **Outbound Ports:** LLM inference client, Speech-to-Text provider, Kafka dispatcher.

### 4. Hybrid AI Agent Engine with MCP (Model Context Protocol)
To ensure the project functions as an enterprise showcase without running up costs:
* **Dual Processing Strategy:**
  * **Simulation Strategy (Load Testing):** Deterministic mock engine parsing incoming utterances via regex and heuristics, generating mock structured tickets within 50 ms to stress-test the broker and database up to 10,000 concurrent calls.
  * **Production Inference Strategy (Single / Live Calls):** Deepgram Nova-2 (STT) -> Ollama (Local Llama 3) or Claude / OpenAI API -> Cartesia / ElevenLabs (TTS).
* **Model Context Protocol (MCP) Integration:**
  The agent interacts with internal services via standard MCP tools:
  * `mcp-crm-server`: Searches customer records by National Identity Number / Account ID.
  * `mcp-ticket-server`: Validates and submits support tickets.
  * `mcp-telephony-routing`: Checks operator availability in real time.

### 5. Routing, Handoff & Operator Dispatch
* Consumes `TicketFormFilledEvent` from Kafka.
* Queries Redis for an active, available human operator matched by skill group (e.g., Billing, Technical Support).
* If an operator is available:
  1. Assigns the call session to the operator's queue.
  2. Pushes the fully compiled ticket and interaction transcript to the Operator UI via WebSocket.
  3. Sends a reroute signal to the Voice Gateway to bridge the customer's audio stream directly to the operator's audio session.
* If no operator is available, places the call into a prioritized FIFO queue, emitting audio events to stream hold music back to the gateway.

### 6. Observability, Backpressure & Resilience
* **Consumer Lag Monitoring:** Prometheus metrics capturing `kafka_consumergroup_lag` across partitions.
* **Reactive Backpressure:** Netty downstream buffers apply pause/resume mechanisms on WebSockets if Kafka ingestion latency exceeds thresholds.
* **Dead Letter Queue (DLQ):** Unparseable payloads or corrupted audio chunks land on `telephony.calls.dlq` for isolated inspection without halting partition consumption.
* **End-to-End Tracing:** Trace IDs propagated from the incoming WebSocket frame, through Kafka headers, down to database persistence.

---

## 5. Agent Specification: `AGENTS.md`

Add this configuration file to the root of your repository to document the operational boundary of the AI agents within the platform:

```markdown
# Agent Directives & Operational Boundaries

## System Identity & Objectives
The agent acts as an automated first-line Tier-1 Customer Support Triage Specialist. Its objective is to collect mandatory incident details from the caller accurately, validate identity constraints, and compile a finalized support ticket payload before executing an operator transfer.

## Conversation Rules
1. **Brevity:** Keep spoken responses under 25 words per turn to maintain conversational flow over voice.
2. **Direct Verification:** When collecting sensitive identifiers (e.g., National ID, Phone Numbers, Account Codes), immediately repeat the detected value using clean alphanumeric spacing for acoustic verification.
3. **Scope Enforcement:** Do not answer open-ended queries unrelated to company support services. Firmly redirect the user back to the ticket collection flow.
4. **Handoff Condition:** Call the `transfer_to_human_operator` tool only when all required schema fields are non-null and confirmed.

## Available MCP Tools

### `mcp-crm-server`
* **`lookup_customer(national_id: string)`**: Returns customer profile and active service contracts.

### `mcp-ticket-server`
* **`create_draft_ticket(customer_id: string, category: string, summary: string)`**: Initializes a draft ticket in the operational database.
* **`finalize_ticket(ticket_id: string, resolution_notes: string)`**: Commits the collected ticket for handoff.

### `mcp-telephony-routing`
* **`check_queue_status(department: string)`**: Checks wait times and available human operator slots.
* **`transfer_to_human_operator(call_id: string, department: string, ticket_id: string)`**: Triggers the SIP/WebSocket bridge transfer.
```

---

## 6. Repository Layout

```
voicepulse/
├── .github/
│   └── workflows/ci.yml
├── AGENTS.md                          # AI System prompts, MCP tool definitions, boundaries
├── docker-compose.yml                 # Full stack (Kafka, Redis, Postgres, Observability)
├── docker-compose.infra.yml           # Base infrastructure only
├── docs/
│   ├── architecture/c4-container.png
│   └── benchmarks/load-test-results.md
├── edge-gateway-service/              # Spring Boot 3 WebFlux (Netty)
│   ├── src/main/java/.../gateway/
│   │   ├── domain/model/              # Pure domain logic
│   │   ├── infrastructure/ws/         # Netty WebSocket handler
│   │   └── infrastructure/kafka/      # Outbound Kafka producer
├── conversation-engine/               # Core state machine & LLM triage
│   ├── src/main/java/.../engine/
│   │   ├── domain/statemachine/
│   │   ├── domain/ports/
│   │   ├── infrastructure/ai/mcp/     # MCP Client integration
│   │   └── infrastructure/kafka/      # Kafka partition consumers
├── routing-handoff-service/           # Operator routing & ticket persistence
├── browser-softphone/                 # Browser client (WebRTC/WS) for zero-cost testing
│   ├── index.html
│   └── src/audio-worklet-processor.js
├── load-generator/                    # High-concurrency client (Go or Kotlin Coroutines)
│   ├── cmd/main.go
│   └── scenarios/stress-call.json
├── observability/
│   ├── prometheus/prometheus.yml
│   └── grafana/dashboards/telephony-metrics.json
└── README.md
```

---

## 7. Concrete Next Steps to Implement

1. **Spin up Infrastructure:** Set up `docker-compose.infra.yml` with single-node KRaft Kafka, Redis 7, and PostgreSQL 16.
2. **Build the Audio Echo Loop:** Write the `browser-softphone` capturing mic input and stream it over WebSockets to `edge-gateway-service`, echoing it back to prove low-latency binary transport before writing the Kafka layer.
3. **Implement Kafka Partitioning:** Pipe audio chunks into Kafka using `call_id` as the message key, and write a consumer worker verifying strict sequential order under simulated packet generation.
4. **Hook up the State Machine & MCP Tool:** Implement the ticket schema extraction using the deterministic `MockLLMProcessor` for high-throughput benchmarks, and an OpenAI Realtime / Ollama adapter for live voice demos.
