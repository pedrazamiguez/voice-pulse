# Rule 02: Java 21 & Concurrency Standards

## Context & Purpose
VoicePulse leverages Java 21 features to guarantee high throughput, immutability, type safety, and predictable memory footprint under thousands of concurrent voice sessions.

---

## 1. Immutable Data Modeling with Java Records

* Every DTO, event object, value object, and message payload MUST be declared as a `record`.
* Records ensure thread-safety, shallow immutability, and zero boilerplate:
  ```java
  public record AudioPacket(
      UUID callId,
      long sequenceNumber,
      long timestampEpochMs,
      byte[] payload
  ) {
      public AudioPacket {
          Objects.requireNonNull(callId, "callId must not be null");
          Objects.requireNonNull(payload, "payload must not be null");
      }
  }
  ```
* Avoid traditional mutable POJOs with setters. Domain entities should return new instances or use encapsulated state transitions.

---

## 2. Sealed Interfaces for State Machines & Domain Events

* Conversational call state transitions and telephony signaling events must be strictly bounded and modeled using `sealed interface`:
  ```java
  public sealed interface CallSessionState permits
      ConnectedState,
      CollectingPassengerDetailsState,
      AwaitingBookingConfirmationState,
      OperatorHandoffState,
      TerminatedState {

      UUID callId();
  }
  ```
* Pattern matching with exhaustive switch statements prevents unhandled states:
  ```java
  public CallSessionState transition(CallSessionState current, CallEvent event) {
      return switch (current) {
          case ConnectedState c -> handleConnected(c, event);
          case CollectingPassengerDetailsState cp -> handleCollecting(cp, event);
          case AwaitingBookingConfirmationState ac -> handleConfirmation(ac, event);
          case OperatorHandoffState oh -> handleHandoff(oh, event);
          case TerminatedState t -> t; // Terminal
      };
  }
  ```

---

## 3. Concurrency Model: Event Loop vs. Virtual Threads

* **Netty Reactive Edge (WebFlux):**
  - Uses the Netty non-blocking event-loop thread pool.
  - **Rule:** NEVER execute blocking I/O, JDBC calls, or long compute operations inside Netty worker threads.
* **Worker & Heavy Task Offloading:**
  - Where blocking operations cannot be avoided (e.g., legacy SDKs, synchronous HTTP client calls), offload execution to Virtual Thread task executors:
  ```java
  try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> blockingService.fetchLegacyRecord(id));
  }
  ```
* **Thread Safety for Shared Sessions:**
  - Active call sessions are pinned by `callId`. Concurrent state mutations must be synchronized or serialized via Kafka partition assignment so only one thread processes a given `callId` at a time.
