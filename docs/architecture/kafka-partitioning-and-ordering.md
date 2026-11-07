# Kafka Partitioning & Event Ordering Strategy

> [!IMPORTANT]
> **Source of Truth:** Defines Kafka topic topology, partition key mandates, and sequential consumption guarantees for VoicePulse (AGENTS.md §3).

---

## 1. Partition Key Mandate: `callId.toString()`

* **Rule:** Every message published to telephony topics MUST use `callId.toString()` as the Kafka record key:
  ```java
  ProducerRecord<String, byte[]> record = 
      new ProducerRecord<>(topic, callId.toString(), audioPayload);
  ```
* **Rationale:**
  - Kafka guarantees strict total ordering **only within a single partition**.
  - In a real-time voice conversational pipeline, out-of-order audio packets cause acoustic distortion and garbled transcription.
  - Keying on `callId` ensures all frames, control signals, and state transitions for a given call land in the same partition and are consumed by the same worker thread.

---

## 2. Topic Topology & Configurations

| Topic Name | Purpose | Key | Default Partitions | Replication | Cleanup Policy |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `telephony.calls.raw` | Audio frames & speech chunks | `callId` | 12 | 1 (Local) / 3 (Prod) | Delete (Retention: 1 hour) |
| `telephony.calls.control` | Barge-in interrupts & cutoffs | `callId` | 6 | 1 (Local) / 3 (Prod) | Delete (Retention: 1 hour) |
| `telephony.tickets.intent` | Completed disruption tickets | `callId` | 6 | 1 (Local) / 3 (Prod) | Compact / Delete (7 days) |
| `telephony.calls.dlq` | Poison pills & corrupted packets | `callId` | 3 | 1 (Local) / 3 (Prod) | Delete (14 days) |

---

## 3. Strict In-Order Sequential Consumption

* **Monotonic Sequence Check:** Each `AudioChunkEvent` includes an incrementing `sequenceNumber`. The conversation engine tracks the latest sequence number per `callId`:
  ```java
  if (incomingEvent.sequenceNumber() <= lastObservedSeq) {
      log.warn("Out-of-order or duplicate audio chunk dropped",
          kv("callId", callId),
          kv("expected", lastObservedSeq + 1),
          kv("received", incomingEvent.sequenceNumber()));
      return;
  }
  ```
* **Worker Threading Constraint:** A single Kafka consumer partition MUST NOT distribute records of the same `callId` across an asynchronous thread pool without deterministic ordering queues (e.g., actor model or per-call virtual thread queue).

---

## 4. Producer & Consumer Low-Latency Tuning

Real-time telephony requires sub-30ms message transit time:
* **Producer Configuration:**
  - `acks=1` (Leader acknowledgment) for audio streaming to optimize latency.
  - `compression.type=lz4` (Ultra-fast CPU compression, minimal latency overhead).
  - `linger.ms=0` (Zero artificial batching delay).
* **Consumer Configuration:**
  - `fetch.min.bytes=1` (Deliver frames immediately without waiting for full batch).
  - `enable.auto.commit=true` with short commit interval or manual acknowledgment after processing.
