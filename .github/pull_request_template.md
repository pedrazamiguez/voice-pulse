## Context & Objective
<!-- Provide a clear explanation of the problem, motivation, or target capability. Reference relevant RFCs (RFC-0001, RFC-0002, RFC-0003). -->

Closes #

## Summary of Changes
<!-- Bulleted list of concrete deliverables, modified components, and newly introduced features. -->
- 

## Architectural Guardrails & Invariants Checklist (AGENTS.md)
<!-- Please verify compliance with VoicePulse architectural directives before requesting review. -->

### 1. Hexagonal Boundary & Domain Purity
- [ ] **Pure Domain Core:** No framework annotations/libraries (`org.springframework.*`, `io.netty.*`, `org.apache.kafka.*`, `jakarta.persistence.*`, `com.fasterxml.jackson.*`) in `domain` packages.
- [ ] **Port Contracts:** Inbound driving ports end with `UseCase` (`..domain.ports.inbound`), outbound driven ports end with `Port` (`..domain.ports.outbound`).
- [ ] **Java 21 Modeling:** Immutable value objects and events declared as `record`; state machines use `sealed interface` with exhaustive pattern matching.

### 2. Concurrency & Memory Safety
- [ ] **Netty Memory Safety:** Every allocated or received `ByteBuf` is released in a `finally` block via `ReferenceCountUtil.release(byteBuf)` (or explicitly retained if forwarded).
- [ ] **Kafka Partitioning:** Every event produced to `telephony.calls.raw` or `telephony.calls.control` uses `callId.toString()` as the message key.
- [ ] **Event Loop Safety:** No blocking I/O calls on Netty worker threads; blocking tasks offloaded to Virtual Thread executors.

### 3. Airline Disruption Hotline Rules (RFC-0003)
- [ ] **Turn Brevity:** Voice triage agent prompts adhere to the 25-word maximum turn limit (if applicable).
- [ ] **NATO Phonetic Verification:** Passenger PNR references confirmed acoustically using standard NATO phonetic spelling (if applicable).
- [ ] **MCP Boundary:** Real-world side effects (CRM, flight status, ticket queueing) dispatched via MCP tool servers.

## Local Quality Gates & Verification Evidence
- [ ] `make fast-check` (Spotless + ArchUnit) passes cleanly.
- [ ] `make arch-check` (ArchUnit + 600-line file ceiling) passes with zero violations.
- [ ] Production Java files stay strictly below the 600-line ceiling.
- [ ] SpotBugs audit (`./mvnw test-compile spotbugs:check`) passes with zero bugs.
- [ ] JaCoCo coverage gate (`./mvnw clean test jacoco:report jacoco:check`) verified.
- [ ] Netty leak detection verified with PARANOID level (`-Dio.netty.leakDetection.level=PARANOID`).
