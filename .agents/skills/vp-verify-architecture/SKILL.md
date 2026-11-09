---
name: vp-verify-architecture
description: Run ArchUnit tests, 600-line file checks, Spotless, and SpotBugs audits.
mode: agent
tools:
  - codebase
  - terminalLastCommand
arguments: []
---
# Verify Architecture

This skill validates the repository bytecode rules, code formatting, and complexity boundaries before committing code or submitting a pull request.

---

## 1. ArchUnit Bytecode Verification
Run the ArchUnit test suite to assert domain purity, port definitions, and forbidden framework dependencies:

```bash
./mvnw test -Dtest=ArchitectureTest
```

* **Expected Result:** Build SUCCESS.
* **Failure Remediation:** If a domain class imports Spring, Netty, Kafka, Jackson, or Lombok, remove the import immediately and relocate transport logic into an infrastructure adapter.

---

## 2. Hard 600-Line File Size Audit
Scan all production Java source files to guarantee compliance with the 600-line ceiling:

```bash
find . -type f -name "*.java" ! -path "*/test/*" ! -path "*/target/*" ! -path "*/build/*" -exec wc -l {} + | awk '$1 > 600 {print $0}'
```

* **Pass Criteria:** Empty output (zero files exceeding 600 lines).
* **Failure Remediation:** Decompose god classes into composite pipeline handlers, extract domain strategy classes, or split multi-use controllers.

---

## 3. Formatting, Static Analysis & Lombok Hygiene
Check code style, Lombok annotations, and compiler warnings:

```bash
# Check Spotless formatting
./mvnw spotless:check

# If unformatted, automatically format
./mvnw spotless:apply

# Run SpotBugs & compiler checks
./mvnw compile spotbugs:check
```

* **Lombok Hygiene Check:** Verify infrastructure classes and configuration classes use `@Slf4j` and `@RequiredArgsConstructor` (with `@NonNull` for required fields), and zero manual `LoggerFactory.getLogger` calls remain. Verify zero Lombok imports exist in `domain..` packages.

---

## 4. Netty Direct Memory Leak Audit
Verify that Netty direct memory buffers are properly released across reactive tests:

```bash
./mvnw test -Dio.netty.leakDetection.level=PARANOID
```

* **Pass Criteria:** No `LEAK: ByteBuf.release() was not called` in test logs.
