# AGENTS.md — VoicePulse Engineering Directives & Architectural Guardrails

> [!IMPORTANT]
> **Source of Truth:** This file defines the inviolable architectural constraints, coding standards, memory safety rules, and operational boundaries for AI coding agents and human contributors working on VoicePulse. Every AI agent MUST ingest and adhere to these directives before generating or modifying code.

---

## 1. Architectural Style: Strict Hexagonal Architecture (Ports & Adapters)

VoicePulse enforces a strict separation between domain business logic, application orchestration, and infrastructure transport adapters:

### Domain Core Purity
* The `domain` package within any service (e.g., `com.voicepulse.conversation.domain..`) MUST NOT import any framework annotations, libraries, or transport dependencies:
  - ❌ **Forbidden in Domain:**
    - `org.springframework.*` (No Spring annotations like `@Component`, `@Service`, `@Autowired`, `@Value`)
    - `io.netty.*` (No `ByteBuf`, `ChannelHandlerContext`, `EventLoop`)
    - `org.apache.kafka.*` (No Kafka producers, consumers, or record metadata)
    - `jakarta.persistence.*` / `javax.persistence.*` (No JPA `@Entity`, `@Table`, `@Id`)
    - `com.fasterxml.jackson.*` (Domain models must not contain JSON serialization annotations)
    - `lombok.*` (No `@Data`, `@Getter`, `@Setter`, `@AllArgsConstructor`, `@RequiredArgsConstructor`, `@NoArgsConstructor`, `@Builder`, `@Slf4j`)
  - ✅ **Permitted in Domain:**
    - Standard Java 21 standard library (`java.time.*`, `java.util.*`, `java.math.*`).
    - Pure domain records (`record CallSessionId(...)`, `record AudioChunk(...)`).
    - Sealed interfaces for domain state machines and events.
    - Port interfaces declaring domain business contracts.

### Port Interfaces
* **Location:** All ports MUST reside in `..domain.ports.inbound` or `..domain.ports.outbound`.
* **Driving Ports (Inbound):** Represent domain use cases invoked by primary adapters (e.g., WebSocket handlers, Kafka consumers).
  - Naming Convention: Must end with `UseCase` (e.g., `ProcessAudioChunkUseCase`, `EvaluateBargeInUseCase`, `SynthesizeTicketUseCase`).
* **Driven Ports (Outbound):** Represent secondary SPIs implemented by infrastructure adapters (e.g., persistence repositories, Kafka producers, TTS synthesizers).
  - Naming Convention: Must end with `Port` (e.g., `TicketRepositoryPort`, `AudioFramePublisherPort`, `CustomerLookupPort`).

### Adapters & Dependency Inversion
* Primary adapters (`infrastructure.ws`, `infrastructure.kafka.consumer`) invoke Inbound Ports.
* Secondary adapters (`infrastructure.persistence.postgres`, `infrastructure.kafka.producer`, `infrastructure.mcp`) implement Outbound Ports.
* Infrastructure adapters depend on Domain; Domain **NEVER** depends on Infrastructure.

---

## 2. Java 21 & Concurrency Rules

### Immutability & Event Modeling
* **Records for Data:** All immutable value objects, DTOs, Kafka event payloads, and command objects MUST be declared as Java `record`.
* **Sealed Interfaces for State Machines:** Call session states and domain events MUST use `sealed interface` with exhaustive pattern matching:
  ```java
  public sealed interface CallState permits
      Connected,
      CollectingPassengerDetails,
      AwaitingBookingConfirmation,
      HandoffReady,
      Terminated {}
  ```
* Avoid mutable state outside tightly encapsulated state machine aggregates.

### Netty ByteBuf & Memory Safety
* VoicePulse streams high-frequency binary audio frames (16 kHz / 8 kHz Linear PCM) through Netty WebFlux.
* **Zero Direct Memory Leaks:** Every received `ByteBuf` allocated in Netty pipelines MUST be explicitly released:
  ```java
  try {
      // Process or slice buffer
  } finally {
      ReferenceCountUtil.release(byteBuf);
  }
  ```
* If a buffer is retained and forwarded downstream to an asynchronous publisher, call `byteBuf.retain()` explicitly and ensure downstream consumers release it upon completion or error.
* Integration tests must run with `-Dio.netty.leakDetection.level=PARANOID`:
  ```bash
  ./mvnw test -Dio.netty.leakDetection.level=PARANOID
  ```

### Virtual Threads vs. Reactive Netty Event Loop
* **Edge Gateway:** Uses Netty event-loop non-blocking I/O. **NEVER** block Netty worker threads with synchronous blocking calls, thread sleep, or synchronous I/O.
* **Heavy Blocking I/O:** Any blocking calls (e.g., JDBC queries, legacy REST client calls) must be offloaded to Virtual Thread executors (`Executors.newVirtualThreadPerTaskExecutor()`).

---

## 3. Kafka Partitioning & Event Ordering

VoicePulse processes conversational audio streams and signaling events asynchronously across distributed nodes:

* **Partition Key Mandate:** Every event produced to `telephony.calls.raw` and `telephony.calls.control` MUST use `callId.toString()` as the Kafka message key:
  ```java
  ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, callId.toString(), audioPayload);
  ```
* **Strict In-Order Consumption:** Audio chunks and state transitions for a given call must be consumed in strict temporal sequence. Never distribute chunks of the same `callId` across uncoordinated worker threads without monotonic sequence check (`sequenceNumber`).

---

## 4. Airline Disruption Hotline Business Invariants ([RFC-0003](docs/rfcs/RFC-0003-VOICEPULSE.md))

When generating domain logic, prompts, or tests for the **SkyWay Airlines International Disruption Hotline**:

* **25-Word Brevity Rule:** The voice triage agent MUST adhere to a **25-word maximum limit per conversational turn**. Long monologues degrade real-time telephony pacing and increase latency.
* **Acoustic Phonetic Verification:** Passenger PNR booking references (6 alphanumeric characters) must be confirmed acoustically using standard NATO phonetic spelling (e.g., *Alpha-Bravo-7-Delta-Foxtrot-9*).
* **Deterministic Structured Ticket:** The goal of the voice agent is to populate a structured `AirlineDisruptionTicket`:
  - `passengerName` (String)
  - `pnr` (6 alphanumeric characters)
  - `originalFlightNumber` (e.g., "SW-104")
  - `disruptionReason` ("CANCELED_WEATHER", "DELAYED_MAINTENANCE")
  - `rebookingPreference` ("NEXT_AVAILABLE", "REFUND_REQUESTED")
  - `hotelRequired` (boolean)
  - `specialAssistance` (boolean)
* **MCP Boundary:** All real-world actions (CRM customer lookup, flight status check, ticket submission, queue handoff) MUST be dispatched via Model Context Protocol (MCP) tool servers. The agent does not execute direct database writes.

---

## 5. Code Quality, File Limits, & Formatting

* **600-Line File Limit:** Production Java source files MUST NOT exceed **600 lines of code**. If a class approaches 500 lines, extract cohesive collaborators (strategy classes, validators, pipeline handlers) before adding new logic.
* **Zero Field Injection:** Prohibit `@Autowired` on class fields. All dependencies MUST be injected via constructor injection. In infrastructure adapters, configurations, and application services, use Lombok `@RequiredArgsConstructor` (with `@NonNull` for required fields) to eliminate constructor boilerplate. In `domain..` classes, use standard Java constructors with explicit validation (`Objects.requireNonNull`). Lombok is strictly prohibited in domain classes.
* **No Wildcard Imports:** Explicit class imports only (e.g., `import java.util.List;`, NEVER `import java.util.*;`).
* **Structured Logging Only:** **NEVER** use `System.out.println()` or `System.err.println()`. Use SLF4J with structured key-value logging. Prohibit manual `private static final Logger log = LoggerFactory.getLogger(...)` declarations in infrastructure and application classes; use Lombok `@Slf4j` instead. Domain classes must remain pure and do not log directly:
  ```java
  log.info("Audio frame ingested", kv("callId", callId), kv("seq", sequenceNumber), kv("bytes", frameSize));
  ```
* **Deterministic Build Tooling:** All builds, tests, and plugin executions MUST use the Maven Wrapper (`./mvnw` or `$(MVN)`) or unified Makefile targets. Never invoke ambient system `mvn` directly.
* **Formatting:** All Java code must format cleanly with Google Java Format (AOSP style, 4-space indent) via `./mvnw spotless:apply` (or `make format`).

---

## 6. Specification-Driven Development (SDD) Workflow for Agents

All issues and feature additions must follow the SDD workflow:
0. **Create Issue (If New):** Invoke `.agents/skills/vp-create-issue/SKILL.md` to convert ideas, bug reports, or screenshots into structured GitHub issues with human approval.
1. **Plan First:** Invoke `.agents/skills/vp-plan-issue/SKILL.md` to produce an approved architecture change contract before creating or editing code files.
2. **Execute with TDD & Verify CI (Definition of Done):** Invoke `.agents/skills/vp-start-issue/SKILL.md` to implement domain unit tests first, followed by ports, and finally infrastructure adapters. The implementation task is ONLY considered done when the dedicated branch is pushed, the Pull Request targeting `develop` is autonomously opened, and all remote GitHub Actions CI status checks pass (`✓`).
3. **Verify Locally (Pre-PR Gate):** Invoke `.agents/skills/vp-verify-architecture/SKILL.md` or `make pre-pr` to run ArchUnit tests, 600-line audit, Spotless, and SpotBugs compiler audits before pushing.
4. **Review PR:** Invoke `.agents/skills/vp-review-pr/SKILL.md` to audit PR diffs against approved issue plans, CI status checks, and architectural invariants before merging.
5. **Address Review Feedback:** Invoke `.agents/skills/vp-address-pr-comments/SKILL.md` to resolve PR review comments and CI feedback.

### Scope Evolution & Realignment Skills
* **In-Flight Scope Additions (Active Issues):** When new requirements or edge cases are uncovered during implementation, invoke `.agents/skills/vp-follow-up/SKILL.md` (Mode A) to append a structured `[SCOPE ADDENDUM]` to the active issue/plan without discarding current progress.
* **Architectural Realignment (Invalidated Plans):** If major architectural blockers or contract breaks invalidate an active plan, invoke `.agents/skills/vp-replan-issue/SKILL.md` to draft a revised plan.
* **Post-Merge Follow-Ups (Merged Work):** When follow-up enhancements or deferred edge cases are identified for merged/closed work, invoke `.agents/skills/vp-follow-up/SKILL.md` (Mode B) to spin off a new tracked GitHub issue explicitly linked to `#<parent_id>` and the merged PR.

---

## 7. Git Workflow & GitHub Management Invariants

Every agent and human contributor MUST adhere to these strict operational constraints:

### Branch Discipline & PR Mandate
* ❌ **NEVER commit or push directly to `main` or `develop`.**
* All development, documentation, fixes, and internal tasks MUST occur on dedicated branches using canonical prefixes:
  - Features: `feature/<description>` (targets `develop`)
  - Bug fixes: `bugfix/<description>` (targets `develop`)
  - Hotfixes: `hotfix/<description>` (targets `main` ONLY)
  - Releases: `release/<description>` (targets `main` ONLY)
  - Documentation: `docs/<description>` (targets `develop`)
  - Refactoring: `refactor/<description>` (targets `develop`)
  - Internal tooling/infrastructure: `internal/<description>` (targets `develop`)
  - Post-release sync back-merges: `sync/main-to-develop-<sha>` (targets `develop`)
* Legacy prefixes such as `feat/` and `fix/` are strictly prohibited.
* All changes must land on `develop` or `main` strictly via Pull Requests verified by CI. Direct pushes to `develop` or `main` are rejected locally by `.git/hooks/pre-push`.

### Release Management & Merge Commit Mandate
* ❌ **NEVER use "Squash and merge" or "Rebase and merge" when merging Pull Requests into `main`.**
* Merges into `main` (for releases and hotfixes) MUST strictly use **"Create a merge commit"**. This preserves full commit history, ensures accurate git tag ancestry for changelog generation, and allows the automated `sync/main-to-develop` workflow to back-merge changes into `develop` cleanly without phantom conflicts or duplicate histories.

### Monotonic Issue Numbering Invariant
* ❌ **NEVER calculate, guess, or prepend arbitrary `[VP-XX]` or `[EPIC-XX]` tags to issue titles, plans, or checklists.**
* GitHub automatically assigns monotonic identifiers (`#<id>`) sequentially across issues and pull requests. Pre-allocating manual identifiers causes desynchronization when pull requests and issues interleave.
* Always refer to phases descriptively (e.g. `Phase 1: Domain Models & Ports`) or use the true GitHub-generated `#<id>` once created.

### Human Approval Gate for GitHub Mutations
To maximize agentic velocity while ensuring strict human governance over repository state and code merges:
* ✅ **Autonomous Agent Operations (Permitted without approval prompts):**
  - Creating and switching to dedicated task branches (`feature/*`, `bugfix/*`, `internal/*`).
  - Committing and pushing to the dedicated remote task branch.
  - Autonomously opening Pull Requests targeting `develop` via `gh pr create` upon completing local quality verification (`make pre-pr`).
  - Polling and self-healing remote CI checks until 100% green (`✓`).
* ❌ **Human-Gated Operations (Strict Developer Approval Required):**
  - **NEVER** execute `gh issue create` autonomously without presenting the complete drafted specification and receiving explicit human confirmation.
  - **NEVER** execute `gh pr review --approve` or `gh pr review --request-changes` autonomously without human review confirmation.
  - **NEVER** execute `gh pr merge` autonomously. Code merges are strictly human privileges.
  - **NEVER** commit or push directly to protected branches (`main`, `develop`).
