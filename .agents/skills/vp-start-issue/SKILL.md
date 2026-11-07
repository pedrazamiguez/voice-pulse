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
* Implement pure Java `record` models and `sealed interface` state machines.
* Write pure domain unit tests using JUnit 5 and AssertJ.
* **Verification:** Run `./mvnw test -Dtest=*DomainTest` (or module equivalent). Must pass with zero Spring/Kafka dependencies.

### Step 2: Port Interfaces
* Declare Inbound (`*UseCase`) and Outbound (`*Port`) Java interfaces in `..domain.ports..`.
* Keep interfaces minimal, focused, and free of framework imports.

### Step 3: Infrastructure Adapters
* Implement primary adapters (WebFlux WebSocket handlers, Kafka message listeners).
* Implement secondary adapters (Kafka producers, PostgreSQL repositories, Redis clients).
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
1. **Fast Check:**
   - Run `make fast-check` (executes Spotless formatting check and ArchUnit rules via `./mvnw`).
   - If Spotless fails, run `./mvnw spotless:apply` (or `make format`).
2. **File Size Hard Gate:**
   - Verify no Java file exceeds 600 lines:
     ```bash
     find . -name "*.java" ! -path "*/test/*" ! -path "*/target/*" -exec wc -l {} + | awk '$1 > 600 {print $0}'
     ```
   - Any output indicates an immediate violation that must be resolved prior to committing.
3. **Commit & PR:**
   - Commit changes with conventional commit syntax (e.g. `feat(arch): ...`).
   - Push branch and open PR targeting `develop`.
