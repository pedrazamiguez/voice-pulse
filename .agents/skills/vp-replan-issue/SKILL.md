---
name: vp-replan-issue
description: Replan an issue when scope changes, unexpected architectural blockers arise, or tests fail repeatedly.
mode: agent
tools:
  - codebase
  - githubRepo
arguments:
  - name: issue_number
    description: The GitHub issue number to replan.
    required: true
  - name: reason
    description: Explanation of why replanning is required.
    required: true
---
# Replan Issue #$ISSUE_NUMBER

Use this skill when implementation uncovers unanticipated complexity, breaking changes in domain contracts, or repeated test failures that invalidate the original plan.

---

## 1. Document the Blocker
* Summarize the root cause or blocking condition:
  - Did ArchUnit reject an intended design?
  - Was an unforeseen external dependency needed?
  - Did the proposed class size threaten the 600-line ceiling?

## 2. Re-evaluate Architecture & Contracts
* Review [`AGENTS.md`](file:///home/antigravity/pedrazamiguez/voice-pulse/AGENTS.md) to ensure any new strategy remains compliant with Hexagonal boundaries.
* Identify alternate design strategies (e.g., event-driven decoupled ports instead of direct method invocation).

## 3. Post Revised Plan for Developer Review
* Draft an updated architecture change plan detailing:
  - What parts of the previous plan are discarded.
  - New interfaces, models, and test cases.
* Post the updated plan to issue #`$ISSUE_NUMBER` with the prefix `[REVISED PLAN]`.
* Request human approval before resuming implementation with `vp-start-issue`.
