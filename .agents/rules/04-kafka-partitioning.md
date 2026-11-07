# Rule 04: Kafka Partitioning & Event Ordering

## Context & Purpose
Conversational audio and telephony control signaling are strictly time-sensitive. If audio packets, Voice Activity Detection (VAD) cutoff flags, or customer responses arrive out of order, the conversation state machine will corrupt state transitions and synthesize incorrect tickets.

---

## 1. Partition Key Mandate

* **Rule:** Every message published to `telephony.calls.raw` and `telephony.calls.control` MUST use the stringified call identifier (`callId.toString()`) as the Kafka partition key:
  ```java
  ProducerRecord<String, AudioPacketMessage> record = 
      new ProducerRecord<>(
          "telephony.calls.raw",
          callId.toString(),  // Partition key
          audioPacketMessage
      );
  ```
* **Consequence of Violation:** Publishing without a key or using a random key distributes audio chunks for the same call across multiple partitions. Different consumer threads would then consume chunks out of order.

---

## 2. Monotonic Sequence Numbering

* Every raw audio chunk and control frame produced at the edge must include a monotonically increasing `sequenceNumber` (0, 1, 2, ...):
  ```java
  public record AudioChunkMessage(
      UUID callId,
      long sequenceNumber,
      long timestampEpochMs,
      String payloadBase64
  ) {}
  ```
* Downstream conversation workers must detect gaps or duplicate sequences. If `seq(N) < seq(N-1)`, the late packet must either be buffered or routed to `telephony.calls.dlq`.

---

## 3. Dedicated Topics & Partition Topologies

| Topic Name | Purpose | Partition Key | Partitions |
|:---|:---|:---|:---:|
| `telephony.calls.raw` | Streaming binary audio chunks from callers | `callId` | 12 |
| `telephony.calls.control` | Real-time signaling (Hangup, Barge-In, DTMF) | `callId` | 12 |
| `telephony.tickets.intent` | Synthesized disruption ticket events | `callId` | 6 |
| `telephony.calls.dlq` | Unparseable or malformed audio packets | `callId` | 3 |

* Producers must configure `acks=all` and idempotence enabled (`enable.idempotence=true`) for control and ticket topics.
