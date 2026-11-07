---
name: vp-create-issue
description: Convert an idea, plain English description, or screenshots into a structured VoicePulse GitHub issue with human approval.
mode: agent
tools:
  - codebase
  - githubRepo
  - terminalLastCommand
arguments:
  - name: input
    description: Raw idea, plain English problem description, or path to screenshot/log image.
    required: false
---
# Create Issue

This skill guides the AI agent through ingesting unstructured input (ideas, plain English problem descriptions, error logs, or screenshots) and crafting a production-grade, well-informed GitHub issue adhering to VoicePulse Specification-Driven Development (SDD) standards.

---

## CRITICAL INVARIANT: Human Approval Gate
> [!IMPORTANT]
> **NEVER** create the GitHub issue autonomously without explicit human approval.
> The agent **MUST** present the complete drafted issue (Title, Labels, Epic, and Markdown Body) to the developer and wait for their explicit confirmation before running any `gh issue create` command.

---

## 1. Input Ingestion & Analysis

The agent can receive input in several forms:
1. **Plain English Idea or Requirement:** A conceptual feature request, design idea, or architectural need.
2. **Bug Report or Error Log:** A description of broken behavior, unexpected output, stack traces, or terminal logs.
3. **Screenshots or Visual Evidence:** Paths to images showing UI errors, architectural diagrams, terminal stack traces, or console outputs.
   - Inspect any visual assets or screenshots provided by the user using available file reading or multimodal inspection tools.
   - Extract key textual data: error messages, exception types, HTTP/WebSocket status codes, timestamps, and UI components involved.

---

## 2. Context Discovery & Project Alignment

Before drafting, gather repository context to make the issue well-informed:

1. **Determine Next Issue Identifier:**
   - Check existing issues to calculate the next sequential `[VP-XX]` ticket tag:
     ```bash
     gh issue list --state all --limit 20
     ```
   - Suggest the next sequential tag (e.g., if highest is `[VP-08]`, designate `[VP-09]`).

2. **Identify Affected Modules & Scope:**
   - Map the requirement or bug to one or more VoicePulse Maven submodules or directories:
     - `shared-kernel`: Pure domain value objects (`CallId`, `Pnr`, `FlightNumber`), shared events, zero external framework dependencies.
     - `edge-gateway-service`: Netty reactive WebFlux, WebSocket audio streaming, low-level audio chunk ingestion.
     - `conversation-engine`: Hexagonal core state machines (`CallSession`), AI triage logic, disruption hotline business rules (RFC-0003).
     - `routing-handoff-service`: Operator queueing, Redis presence, ticket persistence.
     - `.agents/` / Root: Agent skills, rules, `Makefile`, Docker infrastructure, CI/CD workflows.

3. **Align with Governing RFCs:**
   - Reference relevant architectural blueprints:
     - [`RFC-0001`](file:///home/antigravity/pedrazamiguez/voice-pulse/RFC-0001-VOICEPULSE.md): Architecture Strategy & Hexagonal Boundaries.
     - [`RFC-0002`](file:///home/antigravity/pedrazamiguez/voice-pulse/RFC-0002-VOICEPULSE.md): AI Engineering, Tooling & Automation Blueprint.
     - [`RFC-0003`](file:///home/antigravity/pedrazamiguez/voice-pulse/RFC-0003-VOICEPULSE.md): Airline Disruption Hotline Scenario.

---

## 3. Label Classification & Epic Association

Every VoicePulse issue must have accurate labels applied. Inspect existing repository labels dynamically:
```bash
gh label list
```

### A. Mandatory Type Label (Select at least one)
* `bug`: Unexpected behavior, regressions, memory leaks, unhandled exceptions.
* `enhancement`: New feature, capability, performance improvement, or refactoring.
* `documentation`: Documentation additions, diagrams, RFC revisions.

### B. Component & Domain Labels (Add all that apply)
* `architecture`: Hexagonal scaffolding, domain boundaries, ports & adapters, ArchUnit rules.
* `tooling`: Developer CLI (`Makefile`), linters, Spotless, Git hooks, scripts.
* `infrastructure`: Docker compose, Kafka KRaft, Redis, PostgreSQL.
* `ai-workflow`: Agent directives (`AGENTS.md`), skills, rules, CPU RAG indexer.
* `ci-cd`: GitHub Actions workflows, branch protection, PR automation.

### C. Epic Association
* Query active tracking epics in the repository:
  ```bash
  gh issue list --label epic --state open
  ```
* If the issue aligns with an active epic (e.g., `[EPIC-01] Engineering Foundation, Tooling, & Agentic Workflow Setup`), attach the matching epic label (e.g., `epic-1`).
* If the issue does not belong to any current epic or starts a new initiative, do not attach an epic label (or propose creating an epic).

---

## 4. Issue Structure & Quality Standards

Format the issue draft adhering to the official VoicePulse template:

### Title Format
`[VP-XX] <type>(<scope>): <concise imperative summary>`

*Examples:*
* `[VP-09] feat(core): implement CallSession state machine aggregate`
* `[VP-10] fix(gateway): release retained Netty ByteBuf on client disconnect`
* `[VP-11] feat(infra): provision Redis presence store in docker-compose`

### Body Template
```markdown
## Context & Objective
[Detailed explanation of the problem, motivation, or target capability. Reference relevant RFCs (RFC-0001, RFC-0002, RFC-0003) and architectural background.]

## Scope & Deliverables
• Module/Component 1: [Concrete implementation or bugfix details]
• Module/Component 2: [Concrete implementation or bugfix details]
• Tests & Verification: [Target test coverage and assertions]

## Acceptance Criteria
- [ ] Criterion 1 (specific, testable behavior)
- [ ] Criterion 2 (specific, testable behavior)
- [ ] Maven test suite passes (`mvn test`)
- [ ] ArchUnit rules pass with zero violations (`mvn test -Dtest=ArchitectureTest`)
- [ ] Code formatted cleanly with Google Java Format (`mvn spotless:check`)
- [ ] Zero source files exceed the 600-line ceiling

## Architectural Invariants & Guardrails
• Domain Purity: No Spring, Netty, Kafka, Jackson, or JPA imports in domain classes.
• Memory Safety: Explicit Netty `ByteBuf` release via `ReferenceCountUtil.release()` (if applicable).
• Concurrency: Kafka partition key set to `callId.toString()` (if applicable).
• Airline Hotline Rules: Max 25-word turn limit and NATO phonetic PNR validation (if applicable).
```

---

## 5. Human Approval Gate (Mandatory)

1. Present the complete issue draft to the developer:
   - **Title:** `[VP-XX] type(scope): summary`
   - **Labels:** `label1, label2, epic-X`
   - **Markdown Body:** Rendered for developer review.
2. Ask for explicit approval:
   > *"Here is the drafted GitHub issue based on your input. Do you approve creating this issue with the specified title, body, and labels, or would you like to make any modifications?"*
3. **WAIT** for the developer's response. Do NOT proceed to Step 6 without an explicit "yes", "proceed", or "approved".

---

## 6. Issue Creation & Handoff

Once the developer gives explicit approval:

1. **Execute GitHub Issue Creation:**
   ```bash
   gh issue create \
     --title "<Drafted Title>" \
     --body "<Drafted Body>" \
     --label "<label1>,<label2>,<epic-label>"
   ```

2. **Report Issue Details:**
   - Display the created issue number and clickable URL.
   - If associated with an Epic issue, note that the epic tracking checklist can be updated.

3. **Next Step Handoff:**
   - Direct the developer to the next step in the VoicePulse SDD lifecycle:
     > *"Issue #<ID> has been created! To begin the architectural design phase, invoke `.agents/skills/vp-plan-issue/SKILL.md` with issue number `<ID>`."*
