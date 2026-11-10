---
name: vp-start-issue
description: Implement a planned issue by strictly following the posted GitHub implementation plan.
mode: agent
tools:
  - codebase
  - githubRepo
  - terminalLastCommand
arguments:
  - name: issue_number
    description: The GitHub issue number to implement.
    required: true
---
# Start Issue #$ISSUE_NUMBER

This skill executes the implementation phase of the Specification-Driven Development (SDD) cycle for VoicePulse.

---

## 1. Plan Retrieval & Dedicated Branch Synchronization Gate
1. **Retrieve Authoritative Plan:**
   - Inspect issue #`$ISSUE_NUMBER` comments for the approved plan posted by `vp-plan-issue`:
     ```bash
     gh issue view $ISSUE_NUMBER --comments
     ```
   - **HALT CONDITION:** If no approved plan exists on the issue, **STOP IMMEDIATELY**. Instruct the developer to run `vp-plan-issue` first.
   - Extract the designated branch name from the plan (e.g. `feature/VP-01-scaffolding` or `feature/VP-$ISSUE_NUMBER`).

2. **Retrieve Latest Changes from `develop`:**
   - Always update the local `develop` branch with the latest changes from `origin`:
     ```bash
     git checkout develop
     git pull origin develop
     ```

3. **Dedicated Branch Creation & Synchronization:**
   - Ensure you are working on the dedicated feature or bugfix branch, never directly on `develop` or `main`:
     - If the branch does not exist locally, create and switch to it from the up-to-date `develop`:
       ```bash
       git checkout -b <branch-name> develop
       ```
     - If the branch already exists locally, switch to it and synchronize with latest `develop`:
       ```bash
       git checkout <branch-name>
       git merge develop
       ```

---

## 2. Pre-Implementation File Size Audit
* Before modifying any existing Java source file, check its line count:
  ```bash
  wc -l <target-file>
  ```
* **Guard:** If the file already exceeds 500 lines, extract helper classes or strategy delegates before adding any new logic.

---

## 3. Strict TDD Implementation Sequence

Execute implementation in strict layers:

### Step 1: Pure Domain Layer (Hexagonal Core)
* Implement pure Java `record` models and `sealed interface` state machines (zero Lombok, Spring, Netty, Kafka, or Jackson imports).
* Write pure domain unit tests using JUnit 5 and AssertJ.
* **Verification:** Run `./mvnw test -Dtest=*DomainTest` (or module equivalent). Must pass with zero Spring/Kafka/Lombok dependencies.

### Step 2: Port Interfaces
* Declare Inbound (`*UseCase`) and Outbound (`*Port`) Java interfaces in `..domain.ports..`.
* Keep interfaces minimal, focused, and free of framework imports.

### Step 3: Infrastructure Adapters
* Implement primary adapters (WebFlux WebSocket handlers, Kafka message listeners).
* Implement secondary adapters (Kafka producers, PostgreSQL repositories, Redis clients).
* Use Lombok `@Slf4j` for logging and `@RequiredArgsConstructor` (with `@NonNull` for required fields) for constructor-based dependency injection. Prohibit manual `LoggerFactory.getLogger` declarations.
* Ensure all Netty `ByteBuf` instances are safely released in `finally` blocks.
* Ensure all Kafka event records use `callId.toString()` as the partition key.

### Step 4: Integration Tests
* Write integration tests using Testcontainers (KRaft Kafka, PostgreSQL) or MockMvc / WebTestClient.
* Run tests with Netty leak detection set to `PARANOID`:
  ```bash
  ./mvnw test -Dio.netty.leakDetection.level=PARANOID
  ```

---

## 4. Local Verification & Quality Gate
1. **Pre-PR Static Analysis & Compiler Audit:**
   - Run `make pre-pr` (executes ArchUnit rules, 600-line ceiling audit, Spotless code formatting check, and SpotBugs compiler check via `./mvnw spotless:check test-compile spotbugs:check`).
   - If Spotless fails, run `./mvnw spotless:apply` (or `make format`).
   - If SpotBugs or compiler warnings fail, fix all violations locally before committing.
2. **Unit & Integration Test Suite:**
   - Run `make test` (or `./mvnw test -Dio.netty.leakDetection.level=PARANOID`) to guarantee 100% test pass rate locally.
3. **File Size Hard Gate:**
   - Verify no production Java file exceeds 600 lines (`make arch-check`).
4. **Synchronize AI Knowledge Indexes:**
   - Run `make knowledge-update` to refresh documentation vector embeddings, Graphify topological code graph, and `codebase-memory-mcp` AST symbols with all newly implemented classes, ports, and documentation:
     ```bash
     make knowledge-update
     ```
   - **Verification:** Ensure documentation chunks and code nodes are re-indexed cleanly without errors.
5. **Commit Changes:**
   - Commit all modified and new files using conventional commit syntax (e.g., `feat(<scope>): ...` or `fix(<scope>): ...`).

---

## 5. Autonomous PR Creation, CI Verification Gate & Definition of Done

### Step 1: Push Branch to Remote
* Push the dedicated branch to origin:
  ```bash
  git push -u origin <branch-name>
  ```

### Step 2: Autonomous Pull Request Creation (Zero Human Bottleneck)
* Open the Pull Request targeting `develop` immediately using `gh pr create`:
  ```bash
  gh pr create \
    --base develop \
    --head <branch-name> \
    --title "<type>(<scope>): <concise imperative summary>" \
    --body "<PR body linked to #$ISSUE_NUMBER>"
  ```
* **INVARIANT:** **DO NOT** prompt the developer for permission before creating the PR. Creating a PR for a task branch targeting `develop` is an automated, non-destructive action that allows immediate visibility on GitHub.

### Step 3: CI Status Check Monitoring Gate
* Query and watch the PR check suites until all remote GitHub Actions workflows complete:
  ```bash
  gh pr checks <pr-number> --watch
  ```
  *(Or execute a polling loop querying `gh pr checks <pr-number>` every 15–30 seconds until conclusion).*
* Validate that all mandatory workflows have succeeded:
  - `Static Analysis` (Spotless + SpotBugs)
  - `Coverage and Architecture` (JaCoCo thresholds + ArchUnit rules)
  - `Build and Test` (Unit & Testcontainers integration tests)
  - `Enforce Branch Naming & Targets`
  - `SonarQube Analysis & Quality Gate` (if active)

### Step 4: Autonomous CI Self-Healing
* If any CI workflow fails or is cancelled:
  1. Retrieve failed step logs immediately:
     ```bash
     gh run view <run-id> --log-failed
     ```
  2. Diagnose the root cause (e.g. SpotBugs warning, missing test mock, leak detection, branch target).
  3. Apply code corrections locally.
  4. Re-run local verification: `make pre-pr && make test`.
  5. Commit and push the fix:
     ```bash
     git commit -am "fix(<scope>): resolve CI <check-name> failure"
     git push origin <branch-name>
     ```
  6. Re-monitor CI status checks (`gh pr checks <pr-number> --watch`) until all checks pass (`✓`).

### Step 5: Definition of Done (DoD) Criteria
A task is **ONLY** considered complete when all of the following conditions are met:
- [x] All planned files and deliverables from the issue plan are implemented.
- [x] Local verification passed (`make pre-pr`, `make test`, `make knowledge-update`).
- [x] Branch is committed and pushed to `origin`.
- [x] Pull Request is created targeting `develop` with linked issue (`Resolves #$ISSUE_NUMBER`).
- [x] **ALL GitHub Actions CI checks on the PR pass with 100% green status (`✓`).** Zero failing or skipped critical checks.

### Step 6: Human Review Handoff
* Once all CI checks pass, output a concise summary including the clickable PR link, list of passing checks, and diff summary.
* Suggest human review:
  > *"Pull Request #<PR_NUMBER> is ready and all CI checks have passed! Review, approval, and merging are reserved for the developer. You may run `/vp-review-pr <PR_NUMBER>` to conduct compliance review."*
* **CRITICAL INVARIANT:** **NEVER** autonomously approve (`gh pr review --approve`) or merge (`gh pr merge`). Approval and merging are strictly human privileges.
