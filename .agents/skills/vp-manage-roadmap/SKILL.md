---
name: vp-manage-roadmap
description: Project progress briefing, testing guidance, gap analysis, and roadmap governance for VoicePulse.
mode: agent
tools:
  - codebase
  - githubRepo
  - terminalLastCommand
arguments:
  - name: mode
    description: "Operational mode: status (Executive Status Briefing), test (Testing Playbook), audit (Gap Analysis & Reconciliation), or update (Roadmap Governance). Default: status."
    required: false
  - name: target_epic
    description: "Target epic number or identifier for testing/auditing/updating (e.g., epic-2, epic-3, 3). Optional."
    required: false
  - name: action
    description: "Governance action for 'update' mode: sync-status, add-epic, reorder, or regenerate-mermaid. Optional."
    required: false
---
# Manage Roadmap & Project Briefing

This skill acts as the permanent **Project Navigator & Roadmap Steward** for VoicePulse. As the system scales across 7 multi-module epics, it provides on-demand project status briefings, testing guidance, gap analysis, and roadmap governance.

---

## CRITICAL INVARIANTS

> [!IMPORTANT]
> **Human Approval Gate:**
> **NEVER** modify `ROADMAP.md`, commit roadmap changes, or execute GitHub mutations (`gh issue create`, `gh issue close`, `gh pr merge`) autonomously.
> In **`update`** mode, the agent **MUST** present the complete drafted markdown diff in chat and wait for explicit human approval before modifying files or creating PRs.

> [!IMPORTANT]
> **Monotonic Issue Numbering Invariant:**
> **NEVER** calculate, guess, or prepend arbitrary `[VP-XX]` or `[EPIC-XX]` tags to issue titles, plans, or roadmap entries. Always read true `#<id>` numbers dynamically from GitHub.

> [!NOTE]
> **Read-Only Safety:**
> Modes **`status`**, **`test`**, and **`audit`** are strictly non-destructive read operations that do not modify repository code or issue state.

---

## 1. Mode Resolution & Context Gathering

### Step 1.1: Resolve Operational Mode
Inspect the `mode` argument:
* `status` (default if omitted): Produce an Executive Status Briefing of platform capabilities and progress.
* `test`: Provide an interactive Testing Playbook with exact commands for completed epics.
* `audit`: Conduct a Gap Analysis reconciling GitHub issues against `ROADMAP.md` and master plans.
* `update`: Execute disciplined Roadmap Governance to update `ROADMAP.md` under the Human Approval Gate.

### Step 1.2: Gather Repository Context
Run non-destructive status checks:
```bash
# Git state
git branch --show-current
git status --short
git log -n 5 --oneline

# Roadmap and Epics
gh issue list --label epic --state all --limit 20
```

Read [`ROADMAP.md`](file:///Users/pedrazamiguez/Projects/voice-pulse/ROADMAP.md) to inspect current epic statuses and deliverables.

---

## 2. Mode 1: Executive Status Briefing (`status`)

Invoke via `/vp-manage-roadmap` or `make roadmap-status`.

### Step 2.1: Analyze Platform Capabilities
Examine current codebase capabilities across completed epics:
1. **Epic 1 (Foundation & Architecture):**
   - Java 21 multi-module Maven structure (`shared-kernel`, `edge-gateway-service`, `conversation-engine`, `routing-handoff-service`).
   - ArchUnit bytecode rules enforcing Hexagonal architecture, domain purity, and 600-line ceiling.
   - Containerized backing infrastructure (Kafka KRaft, Redis 7, Postgres 16) and unified `Makefile`.
   - Local AI meta-system: CPU FastEmbed RAG (`voicepulse-rag`), Graphify, and `codebase-memory-mcp`.
2. **Epic 2 (Gateway & Softphone Echo Loop):**
   - Non-blocking Netty WebFlux WebSocket at `/ws/calls/{callId}/audio` (16 kHz Linear PCM).
   - Zero-leak `ByteBuf` reference counting verified under `PARANOID` leak detection.
   - Embedded browser softphone (`http://localhost:8080/softphone/index.html`) with HTML5 `AudioWorklet` downsampler and VU meter.
   - Loopback audio echo adapter with sub-100ms round-trip latency.
3. **Epic 3 (High-Throughput Kafka Event Fabric):**
   - Distributed streaming on `telephony.calls.raw` with custom zero-allocation binary serialization.
   - Strict `callId.toString()` partitioning key mandate.
   - Sequential, pinned in-order worker pool consumer in `conversation-engine`.
   - Dead-letter queue (`telephony.calls.dlq`) fallback and consumer lag metrics.
4. **Epic 4 (Hexagonal Conversation Core, VAD & State Machine):**
   - Pure domain `ConversationSession` state machine using Java 21 `sealed interface CallSessionState`.
   - Real-time DSP `VoiceActivityDetector` (< 1ms RMS energy evaluation) and 500ms `TrailingSilenceEvaluator`.
   - Acoustic NATO phonetic PNR verifier (`Alpha-8-Foxtrot-9-Xray-2`) and 25-word turn brevity guardrail.
   - Real-time caller barge-in evaluation and `BargeInInterrupt` dispatch on `telephony.calls.control` with < 50ms edge buffer purging.
   - Deterministic sub-50ms `MockLlmConversationProcessor` and `DisruptionTicketAssembler` producing `AirlineDisruptionTicket`.

### Step 2.2: Generate Executive Briefing Report
Format the response using the following structure:
* **Repository & Branch Context:** Active branch, latest commit, working tree state.
* **Roadmap Milestone Progress Matrix:** Table listing all 7 epics with badges (`[COMPLETED]`, `[NEXT UP]`, `[PLANNED]`).
* **What Works Today (Plain English):** Highlighting audio ingestion, softphone UI, Kafka streaming, VAD barge-in, state machine, and memory safety.
* **Current Limitations & Context:** Explain that runtime MCP tool servers, live LLM/STT/TTS synthesis, and the Operator Web Console are planned for Epics 5–6.
* **Why No UI for Back-End Phases:** Clarify why Epics 3–4 (Kafka streaming, VAD, and conversational state machine) are verified via Testcontainers or terminal logs while the Operator Web Console arrives in Epic 6.
* **Active Epic & Next Steps:** Outline the immediate next milestone.

---

## 3. Mode 2: Testing Playbook (`test`)

Invoke via `/vp-manage-roadmap mode=test [target_epic=<id>]`.

### Step 3.1: Determine Scope
* If `target_epic` is specified (e.g. `2`, `epic-2`, `3`, `4`), output the dedicated verification playbook for that milestone.
* If omitted, present the full test playbook for all completed milestones.

### Step 3.2: Output Copy-Pasteable Playbooks

#### A. Core Quality & Architecture Gate
```bash
# Rapid check (<15s): Spotless formatting & ArchUnit rules
make fast-check

# Full pre-PR gate: ArchUnit, 600-line file audit, Spotless, SpotBugs
make pre-pr

# Verify AI RAG and changelog automation
make test-rag && make test-changelog
```

#### B. Epic 2 Verification (Voice Gateway & Softphone Echo Loop)
* **Automated Integration Test:**
  ```bash
  ./mvnw test -pl edge-gateway-service -Dtest=AudioEchoIntegrationTest -Dio.netty.leakDetection.level=PARANOID
  ```
  *Assertions:* Streams 5,000 synthetic PCM chunks, validates in-order frame arrival, sub-100ms latency, and zero `ByteBuf` leaks.
* **Interactive Browser Playground (Standalone Loopback Mode):**
  ```bash
  # Start the edge gateway service in loopback echo mode
  ./mvnw spring-boot:run -pl edge-gateway-service \
    -Dspring-boot.run.arguments="--voicepulse.gateway.audio-publisher=loopback"
  ```
  *User Action:* Open `http://localhost:8080/softphone/index.html` in Chrome or Edge.
  *Verification Steps:*
  1. Click **Connect** (status badge turns green `CONNECTED`).
  2. Speak into microphone; verify live VU meter pulses.
  3. Hear audio loopback echoed with sub-100ms latency.
  4. Inspect Network tab for WebSocket binary PCM frames at `/ws/calls/{callId}/audio`.

#### C. Epic 3 Verification (High-Throughput Kafka Event Bus)
* **Automated Testcontainers Integration Test:**
  ```bash
  ./mvnw test -pl conversation-engine -Dtest=KafkaStreamingPipelineIntegrationTest,KafkaDeadLetterQueueIntegrationTest -Dio.netty.leakDetection.level=PARANOID
  ```
  *Assertions:*
  - Starts KRaft Kafka Testcontainers instance.
  - Produces 5,000 continuous audio frames with `callId` partition key.
  - Verifies monotonic sequence continuity (`sequenceNumber`), DLQ routing on corruption, and consumer lag monitoring.
* **Local Docker Infrastructure Run (Optional):**
  ```bash
  make docker-infra && make verify-infra
  ```

#### D. Epic 4 Verification (Hexagonal Conversation Core, VAD & Barge-In)
* **Automated End-to-End Conversational Dialog & Barge-In Testcontainers Test:**
  ```bash
  ./mvnw test -pl conversation-engine -Dtest=EndToEndConversationDialogIntegrationTest -Dio.netty.leakDetection.level=PARANOID
  ```
  *Assertions:*
  - Streams 20ms Linear PCM frames over `telephony.calls.raw` and detects 500ms trailing silence turn boundaries.
  - Verifies NATO phonetic PNR confirmation (`Alpha-8-Foxtrot-9-Xray-2`) and 25-word turn brevity ceiling.
  - Triggers mid-stream caller barge-in during bot playback and verifies `BargeInInterrupt` on `telephony.calls.control`.
  - Compiles structured `AirlineDisruptionTicket` with sub-50ms turn latency.
* **Edge Gateway Control Signaling & Sub-1ms / Sub-50ms Benchmarks:**
  ```bash
  ./mvnw test -pl edge-gateway-service -Dtest=CallControlSignalingIntegrationTest -Dio.netty.leakDetection.level=PARANOID
  ./mvnw test -pl conversation-engine -Dtest=VadPerformanceBenchmarkTest,MockLlmLatencyBenchmarkTest
  ```

---

## 4. Mode 3: Gap Analysis & Reconciliation (`audit`)

Invoke via `/vp-manage-roadmap mode=audit`.

### Step 4.1: Cross-Reference GitHub and Documentation
1. **GitHub Issues vs. ROADMAP.md:**
   - Fetch closed and open epics:
     ```bash
     gh issue list --label epic --state all --json number,title,state
     ```
   - Parse `ROADMAP.md` Section 1 status table.
   - Detect inconsistencies: e.g. Epic closed on GitHub but marked "Active" or "Planned" in `ROADMAP.md`, or vice-versa.
2. **Master Plan Checklists:**
   - Scan all `docs/plans/*.md` files.
   - Detect unchecked items (`- [ ]`) in plans corresponding to completed epics.
3. **Pull Request & Branch Hygiene:**
   - Query unmerged PRs: `gh pr list --state open`
   - Detect branches missing tracking issues or PRs with failing checks.
4. **Monotonic Issue Numbering Audit:**
   - Check if any recent issue titles or plan files contain hardcoded `[VP-XX]` tags.

### Step 4.2: Output Reconciliation Report
Present an actionable report:
* **Status Alignment Table:** Comparing GitHub issue state vs. `ROADMAP.md` status.
* **Plan Checklist Gaps:** Unchecked deliverables or orphaned documentation.
* **Hygiene Flags:** Branch or numbering violations.
* **Recommended Actions:** Clear steps to resolve each discovered delta.

---

## 5. Mode 4: Roadmap Governance (`update`)

Invoke via `/vp-manage-roadmap mode=update action=<action>`.

### Step 5.1: Enforce Branch Discipline Gate
All modifications to `ROADMAP.md` MUST occur on a dedicated branch:
```bash
git checkout develop
git pull origin develop
git checkout -b docs/roadmap-<action-description> develop
```
❌ **NEVER commit roadmap changes directly to `main` or `develop`.**

### Step 5.2: Supported Governance Actions
* **`sync-status`:** Update epic statuses in Section 1 and Section 2 (e.g. mark Epic 3 as Completed, set Epic 4 as Next / Active).
* **`add-epic`:** Append a new epic with tracking issue, blueprint RFC citations, and execution phases.
* **`reorder`:** Reorder epic sequence and update dependency links.
* **`regenerate-mermaid`:** Update the Mermaid flowchart (`flowchart LR`) reflecting node statuses:
  ```mermaid
  flowchart LR
      E1["Epic 1<br/>Foundation & Tooling<br/>(Completed)"] --> E2["Epic 2<br/>Gateway & Echo Loop<br/>(Completed)"]
      E2 --> E3["Epic 3<br/>Kafka Event Bus<br/>(Completed)"]
      E3 --> E4["Epic 4<br/>Conversation Core & VAD<br/>(Next)"]
      E4 --> E5["Epic 5<br/>MCP Tools & AI Triage"]
  ```

### Step 5.3: Mandatory Human Approval Gate
1. Draft the markdown changes to `ROADMAP.md`.
2. Present the complete proposed diff in chat:
   ```markdown
   Proposed diff for ROADMAP.md:
   ...
   ```
3. **WAIT** for explicit human confirmation. **DO NOT** write to disk or commit before receiving approval.
4. Upon approval:
   - Apply edits to `ROADMAP.md`.
   - Run verification: `make pre-pr && make knowledge-update`.
   - Commit: `git commit -am "docs(roadmap): <concise summary>"`
   - Push: `git push -u origin docs/roadmap-<action-description>`
   - Autonomously open PR targeting `develop`:
     ```bash
     gh pr create --base develop --head docs/roadmap-<action-description> \
       --title "docs(roadmap): update project roadmap" \
       --body "Reconciles ROADMAP.md statuses and Mermaid diagram."
     ```
   - Direct developer to review and merge the PR.
