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

---

## 4. Class Granularity & Zero Nested Classes

* **Top-Level Classes Only:** Nested classes (whether static or non-static `class` / `inner class`) within production classes are strictly forbidden (except Java 21 `record` permits inside a `sealed interface`).
* Every state machine tracker, algorithmic collaborator, and data accumulator MUST reside in its own dedicated, top-level `.java` file with package-private or public visibility.
* This guarantees 100% isolated unit testability without reflection and avoids bidirectional tight coupling.

---

## 5. Single Responsibility Principle (SRP) & Method Sizing

* Every class MUST have one, and only one, reason to change.
* Never conflate registry/cache management, global telemetry aggregation, and per-entity state transition algorithms in a single class. Split coordinators from algorithmic workers.
* **Zero Bloated Private Methods:** If private logic exceeds trivial helper tasks (> 15-20 lines or non-trivial branching), extract it to a cohesive collaborator service or component with its own unit tests.
