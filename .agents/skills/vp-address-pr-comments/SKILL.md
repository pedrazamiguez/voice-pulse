---
name: vp-address-pr-comments
description: Ingest, analyze, and systematically resolve pull request review comments.
mode: agent
tools:
  - codebase
  - githubRepo
  - terminalLastCommand
arguments:
  - name: pr_number
    description: The Pull Request number to address comments on.
    required: true
---
# Address PR Comments #$PR_NUMBER

Use this skill to fetch, diagnose, and resolve human code review feedback and automated CI review comments on an open PR.

---

## 1. Fetch Review Comments
* Ingest all inline review comments and general reviews:
  ```bash
  gh pr view $PR_NUMBER --comments
  gh api repos/:owner/:repo/pulls/$PR_NUMBER/comments --paginate
  ```

## 2. Categorize Feedback
1. **Architectural / Invariant Violations:** e.g., ArchUnit rule breach, forbidden framework import in domain. Fix by adhering to [`AGENTS.md`](file:///home/antigravity/pedrazamiguez/voice-pulse/AGENTS.md).
2. **Formatting & Style:** e.g., missing imports, unused variables, indent issues. Fix with `./mvnw spotless:apply` (or `make format`).
3. **Logic / Edge Case Testing:** e.g., missing test case, unhandled barge-in state. Write new JUnit 5 test cases first, then apply fix.

## 3. Implement & Verify Fixes
* Apply changes to local branch.
* Run `vp-verify-architecture` (ArchUnit + 600-line audit).
* Run test suite: `./mvnw test` (or `make test`).
* Synchronize AI knowledge indexes to reflect review changes:
  ```bash
  make knowledge-update
  ```

## 4. Push & Reply
* Push commit to PR branch.
* Post replies to review comments explaining the resolutions applied.
