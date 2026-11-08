---
name: vp-follow-up
description: Handle in-flight scope amendments for active issues or create linked follow-up issues for merged work with human approval.
mode: agent
tools:
  - codebase
  - githubRepo
  - terminalLastCommand
arguments:
  - name: issue_number
    description: The GitHub issue number to amend or follow up on.
    required: true
  - name: description
    description: Details of the scope amendment or follow-up requirement.
    required: true
  - name: mode
    description: "Operational mode: auto (default, inferred from issue state), in-flight (Mode A: scope addendum for open issues), or post-merge (Mode B: follow-up issue for closed/merged work)."
    required: false
---
# Follow-Up / Scope Amendment #$ISSUE_NUMBER

This skill manages scope evolution within the VoicePulse Specification-Driven Development (SDD) lifecycle. It provides a structured, dual-mode mechanism to handle newly discovered requirements, deferred edge cases, or scope expansions:

1. **Mode A (In-Flight Scope Amendment):** For active, open issues where implementation is in progress on a feature branch. Appends an approved `[SCOPE ADDENDUM]` to the active issue/plan without discarding progress or forcing an unnecessary full replan.
2. **Mode B (Post-Merge Follow-Up Issue):** For closed or merged issues where follow-ups, polish items, or deferred capabilities must be tracked. Drafts and creates a new linked GitHub issue referencing `#<parent_id>` and the merged PR.

---

## CRITICAL INVARIANTS

> [!IMPORTANT]
> **Human Approval Gate:**
> **NEVER** post comments (`gh issue comment`) or create issues (`gh issue create`) autonomously.
> The agent **MUST** present the complete drafted addendum or follow-up issue (Title, Labels, Parent Link, and Markdown Body) in chat and wait for explicit human approval before running any GitHub mutation commands.

> [!IMPORTANT]
> **Monotonic Issue Numbering Invariant:**
> **NEVER** calculate, guess, or prepend arbitrary `[VP-XX]` or `[EPIC-XX]` tags to issue titles.
> GitHub automatically assigns monotonic identifiers (`#<id>`) sequentially. Always format titles strictly using Conventional Commits: `<type>(<scope>): <summary>`.

---

## 1. Mode Resolution & Context Ingestion

### Step 1.1: Query Issue Status
Retrieve the target issue details to determine state and existing scope:
```bash
gh issue view $ISSUE_NUMBER --json state,title,labels,body
```

### Step 1.2: Resolve Operational Mode
* If `mode` is explicitly provided as `in-flight`: Execute **Mode A**. If the issue is `CLOSED`, warn the developer and propose Mode B.
* If `mode` is explicitly provided as `post-merge`: Execute **Mode B**. If the issue is `OPEN`, warn the developer and propose Mode A.
* If `mode` is omitted or `auto` (default):
  * **`state == "OPEN"`** $\rightarrow$ Automatically select **Mode A (In-Flight Scope Amendment)**.
  * **`state == "CLOSED"`** $\rightarrow$ Automatically select **Mode B (Post-Merge Follow-Up Issue)**.

---

## 2. Mode A: In-Flight Scope Amendment Workflow

Use Mode A when active development on an open issue reveals omitted deliverables, unhandled edge cases, or testing gaps that should be included in the active branch rather than spun off into a detached issue.

### Step 2.1: Ingest Existing Plan & Acceptance Criteria
Inspect the active issue and plan comments:
```bash
gh issue view $ISSUE_NUMBER --comments
```
Identify:
1. Current deliverables and architectural contract posted by `vp-plan-issue`.
2. Existing acceptance criteria checkboxes in the issue body.
3. Active feature branch name.

### Step 2.2: Scope Delta & Architectural Blast Radius Analysis
1. **Diff Proposed Changes:** Compare the user's amendment description against existing deliverables to prevent redundancy.
2. **Assess Hexagonal Compliance:** Ensure additional components maintain pure domain separation and port boundaries ([`AGENTS.md`](file:///home/antigravity/pedrazamiguez/voice-pulse/AGENTS.md)).
3. **Verify 600-Line Ceiling:** Confirm that modified files will not exceed the 600-line ceiling. If a target file is close to 500 lines, specify helper extraction in the addendum.
4. **Determine if Full Replan is Warranted:** If the amendment introduces fundamental breaking architectural changes or invalidates core domain models, halt and recommend [`vp-replan-issue`](file:///home/antigravity/pedrazamiguez/voice-pulse/.agents/skills/vp-replan-issue/SKILL.md) instead.

### Step 2.3: Draft `[SCOPE ADDENDUM]` Comment
Format the addendum according to VoicePulse standards:

```markdown
## [SCOPE ADDENDUM]

### Reason for Amendment
[Clear summary explaining why this scope addition was identified during implementation or review]

### Additional Scope & Deliverables
• Module/Component: [Concrete implementation details, new classes, or port additions]
• File Changes:
  - `[NEW] path/to/NewClass.java`
  - `[MODIFY] path/to/ExistingClass.java`

### New Acceptance Criteria
- [ ] [Specific, testable behavior or assertion]
- [ ] [Additional test case or edge case verification]

### Architectural & Blast Radius Impact
• Hexagonal Integrity: [Domain purity preserved / Port signatures added]
• File Size Audit: [All touched files confirmed under 600 lines]
```

### Step 2.4: Human Approval Gate (Mandatory)
1. Present the complete drafted `[SCOPE ADDENDUM]` to the developer.
2. Ask for explicit approval:
   > *"Here is the drafted scope addendum for issue #$ISSUE_NUMBER. Do you approve posting this addendum, or would you like to make any modifications?"*
3. **WAIT** for explicit confirmation ("yes", "approved", "proceed").

### Step 2.5: Post Addendum
Once approved, post the comment to GitHub:
```bash
gh issue comment $ISSUE_NUMBER --body "<Drafted Addendum Body>"
```
Direct the developer to continue implementation on the active branch adhering to the updated criteria.

---

## 3. Mode B: Post-Merge Follow-Up Issue Workflow

Use Mode B when an issue or PR has already merged, but deferred enhancements, secondary edge cases, or non-blocking technical debt must be captured in a newly tracked issue.

### Step 3.1: Retrieve Parent Context & Merged PR
Inspect the closed parent issue and locate its merged Pull Request:
```bash
gh issue view $ISSUE_NUMBER --comments
gh pr list --search "$ISSUE_NUMBER" --state merged --json number,title,url,mergedAt
```

### Step 3.2: Label & Epic Inheritance
Query repository labels and active epics:
```bash
gh label list
gh issue list --label epic --state open
```
1. **Mandatory Type Label:** Select `enhancement` (feature, refactoring) or `bug` (regression, unhandled edge case).
2. **Domain Labels:** Inherit relevant component labels from parent issue (`architecture`, `tooling`, `infrastructure`, `ai-workflow`, `ci-cd`).
3. **Epic Label:** If the parent issue belonged to an active epic (e.g., `epic-1`), preserve the epic association.

### Step 3.3: Draft Follow-Up Issue
Draft the issue adhering to VoicePulse SDD conventions:

#### Title Format
`<type>(<scope>): <concise imperative summary>`  
*(Strictly NO manual `[VP-XX]` prefix)*

#### Body Template
```markdown
## Context & Objective
Follow-up to #$ISSUE_NUMBER (PR #<pr_number>).

[Detailed explanation of the follow-up enhancement, deferred edge case, or uncovered requirement. Explain why it was deferred from the parent issue and the target behavior.]

## Scope & Deliverables
• Module/Component: [Concrete implementation or bugfix details]
• Tests & Verification: [Target test coverage and assertions]

## Acceptance Criteria
- [ ] [Specific, testable behavior]
- [ ] Maven test suite passes (`./mvnw test`)
- [ ] ArchUnit rules pass with zero violations (`./mvnw test -Dtest=ArchitectureTest`)
- [ ] Code formatted cleanly with Google Java Format (`./mvnw spotless:check`)
- [ ] Zero source files exceed the 600-line ceiling

## Architectural Invariants & Guardrails
• Domain Purity: No Spring, Netty, Kafka, Jackson, or JPA imports in domain classes.
• Monotonic Numbering: Rely on GitHub monotonic `#<id>` assignment.
• Human Approval Gate: Strictly enforced before any state mutations.
```

### Step 3.4: Human Approval Gate (Mandatory)
1. Present the complete drafted follow-up issue to the developer:
   - **Title:** `<type>(<scope>): <summary>`
   - **Labels:** `<labels>`
   - **Markdown Body:** Rendered draft.
2. Ask for explicit approval:
   > *"Here is the drafted follow-up issue for #$ISSUE_NUMBER. Do you approve creating this issue, or would you like to make any modifications?"*
3. **WAIT** for explicit confirmation before running `gh issue create`.

### Step 3.5: Execute Issue Creation & Handoff
Once approved:
1. Create the issue:
   ```bash
   gh issue create \
     --title "<Drafted Title>" \
     --body "<Drafted Body>" \
     --label "<label1>,<label2>,<epic-label>"
   ```
2. Report the created issue number and clickable URL.
3. Direct the developer to the planning phase:
   > *"Follow-up issue #<NEW_ID> has been created! To plan the architectural specification, invoke `.agents/skills/vp-plan-issue/SKILL.md` with issue number `<NEW_ID>`."*
