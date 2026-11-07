---
name: vp-plan-issue
description: Plan a VoicePulse GitHub issue by creating a detailed Hexagonal architectural specification.
mode: agent
tools:
  - codebase
  - githubRepo
arguments:
  - name: issue_number
    description: The GitHub issue number to plan.
    required: true
---
# Plan Issue #$ISSUE_NUMBER

This skill guides the AI agent through the **Specification & Architecture** stage of Specification-Driven Development (SDD) before writing any production Java code.

---

## 1. Context & Ingestion
1. **Fetch Issue Description:**
   - Retrieve issue #`$ISSUE_NUMBER` and existing discussion comments using `gh issue view $ISSUE_NUMBER`.
   - Identify the user stories, acceptance criteria, and relevant RFC citations ([`RFC-0001`](file:///home/antigravity/pedrazamiguez/voice-pulse/RFC-0001-VOICEPULSE.md), [`RFC-0002`](file:///home/antigravity/pedrazamiguez/voice-pulse/RFC-0002-VOICEPULSE.md), or [`RFC-0003`](file:///home/antigravity/pedrazamiguez/voice-pulse/RFC-0003-VOICEPULSE.md)).
2. **Review Inviolable Constraints:**
   - Read [`AGENTS.md`](file:///home/antigravity/pedrazamiguez/voice-pulse/AGENTS.md) and relevant `.agents/rules/*.md` files:
     - `01-hexagonal-architecture.md` (Domain purity, Port signatures)
     - `02-java21-concurrency.md` (Records, Sealed state interfaces)
     - `03-netty-bytebuf-safety.md` (ByteBuf reference count release)
     - `04-kafka-partitioning.md` (Strict partition by `callId`)

---

## 2. Architectural Analysis & Blast Radius
1. **Locate Existing Components:**
   - Inspect existing domain models, ports, and adapters in `edge-gateway-service`, `conversation-engine`, `routing-handoff-service`, and `shared-kernel`.
2. **Assess Blast Radius:**
   - Check if any touched existing Java files exceed **500 lines**. If so, schedule refactoring/decomposition in the plan.
3. **Verify Hexagonal Boundaries:**
   - Confirm domain classes will have **zero framework imports** (no Spring, Netty, Kafka, JPA, Jackson).

---

## 3. Produce Formal Implementation Plan
Structure the output plan with the following standard sections:

### A. Context & Goals
* Summary of the issue requirement and target behavior.

### B. Proposed File Changes
Use explicit action markers:
* `[NEW] src/main/java/com/voicepulse/.../domain/model/CallSession.java`
* `[NEW] src/main/java/com/voicepulse/.../domain/ports/inbound/ProcessAudioChunkUseCase.java`
* `[NEW] src/main/java/com/voicepulse/.../infrastructure/ws/AudioWebSocketHandler.java`
* `[MODIFY] pom.xml`

### C. Interface & Data Contracts
* Declare Java 21 `record` and `sealed interface` definitions.
* Specify Inbound (`*UseCase`) and Outbound (`*Port`) signatures.

### D. TDD Test Plan
* **Domain Unit Tests:** Pure unit tests executing with mock ports (`*DomainTest`).
* **ArchUnit Assertions:** Verify architectural integrity (`ArchitectureTest`).
* **Integration Tests:** Testcontainers tests for Kafka/Postgres where applicable.

### E. Memory & Safety Checklist
* [ ] Netty `ByteBuf` released via `ReferenceCountUtil.release()` or `DataBufferUtils.release()`.
* [ ] Kafka messages partitioned strictly by `callId`.
* [ ] All files projected to stay under 600 lines.
* [ ] No `@Autowired` field injection.

---

## 4. Human Approval Gate
1. Present the completed implementation plan to the developer.
2. Once approved, post the plan as a comment to GitHub issue #`$ISSUE_NUMBER`:
   ```bash
   gh issue comment $ISSUE_NUMBER --body "..."
   ```
3. Await developer confirmation before proceeding to `vp-start-issue`.
