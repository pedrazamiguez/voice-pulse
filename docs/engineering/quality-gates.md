# Engineering Quality Gates & Architectural Guardrails

> [!IMPORTANT]
> **Source of Truth:** Defines code quality invariants, compiler analysis gates, and automated architectural boundaries enforced in VoicePulse (AGENTS.md §5).

---

## 1. Domain Core Purity Gate

* **Hexagonal Boundary Invariant:** The `domain` package of any service (`com.voicepulse.<service>.domain..`) MUST remain 100% pure:
  - ❌ **Forbidden in Domain:**
    - `org.springframework.*` (No Spring annotations like `@Component`, `@Service`, `@Autowired`)
    - `io.netty.*` (No `ByteBuf`, `ChannelHandlerContext`, `EventLoop`)
    - `org.apache.kafka.*` (No Kafka producers, consumers, or headers)
    - `jakarta.persistence.*` / `javax.persistence.*` (No JPA `@Entity`, `@Table`)
    - `com.fasterxml.jackson.*` (No `@JsonProperty`, `@JsonSerialize`)
  - ✅ **Permitted in Domain:**
    - Standard Java 21 runtime (`java.time.*`, `java.util.*`, `java.math.*`).
    - Pure domain records (`record CallSessionId(...)`, `record AudioChunk(...)`).
    - Sealed interfaces for state machines and domain events.
    - Port interfaces declaring domain business contracts.

---

## 2. Source Code Invariants

### 1. 600-Line Source File Limit
* Production Java source files (`src/main/java`) MUST NOT exceed **600 lines of code**.
* When a class approaches 500 lines, extract cohesive collaborators (strategy delegates, pipeline handlers, validators).
* Enforced automatically via `make arch-check`.

### 2. Zero Field Injection
* Prohibit `@Autowired` on class fields.
* All dependencies MUST be injected via constructor injection (using standard Java constructors or Lombok `@RequiredArgsConstructor`).

### 3. No Wildcard Imports
* Prohibit wildcard imports (e.g., `import java.util.*;`).
* Use explicit, granular imports only (e.g., `import java.util.List;`).

### 4. Structured SLF4J Logging Only
* **Zero Console Prints:** `System.out.println()` and `System.err.println()` are strictly prohibited.
* Use SLF4J structured key-value logging:
  ```java
  log.info("Call session state transitioned",
      kv("callId", callId),
      kv("previousState", prevState),
      kv("newState", newState));
  ```

---

## 3. Formatting & Static Analysis Gates

| Gate | Tool | Command | Tolerance |
| :--- | :--- | :--- | :--- |
| **Code Formatting** | Spotless (Google Java Format AOSP, 4 spaces) | `make format` / `./mvnw spotless:apply` | Zero formatting diffs |
| **Architecture Rules** | ArchUnit 1.3+ | `make arch-check` | Zero rule violations |
| **Static Bug Analysis** | SpotBugs 4.8+ | `./mvnw spotbugs:check` | Zero high/medium bugs |
| **Compiler Analysis** | Google Error Prone | Included in `./mvnw compile` | Zero compiler warnings as errors |
| **Fast Quality Gate** | Spotless + ArchUnit (< 15s) | `make fast-check` | Mandatory before every commit |
