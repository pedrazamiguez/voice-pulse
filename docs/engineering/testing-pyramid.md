# Testing Pyramid & Verification Strategy

> [!IMPORTANT]
> **Source of Truth:** Defines the multi-tier testing strategy, Testcontainers configurations, and memory safety verification for VoicePulse.

---

## 1. The VoicePulse Testing Pyramid

```
                / \
               /   \      Tier 4: Concurrency & Load Tests
              / Gat \     (Gatling / k6, 10,000 synthetic calls)
             /-------\
            / Test-   \   Tier 3: Testcontainers Integration Tests
           / containers\  (KRaft Kafka, PostgreSQL 16, Redis 7, Netty PARANOID)
          /-------------\
         /  Adapter &    \ Tier 2: Port & Adapter Unit Tests
        /   WebTestClient \ (Mocked outbound ports, WebFlux endpoint contracts)
       /-------------------\
      /     Pure Domain     \ Tier 1: Pure Domain Unit Tests
     /     (JUnit 5 / AssertJ) \ (FSM, Brevity, NATO Phonetics, Zero Spring)
    /-------------------------\
```

---

## 2. Test Tiers & Execution Standards

### Tier 1: Pure Domain Unit Tests
* **Scope:** Domain models, state machines, and ticket validation logic.
* **Execution Time:** < 5 ms per test.
* **Dependencies:** Zero Spring, zero Kafka, zero Netty.
* **Command:** `./mvnw test -Dtest=*DomainTest`

### Tier 2: Port & Adapter Unit Tests
* **Scope:** Primary adapters (WebFlux WebSocket handlers) and secondary SPI adapters.
* **Tooling:** Mockito, `WebTestClient`.
* **Rules:** Verify Netty ByteBuf release in adapter unit tests with mocked downstream publishers.

### Tier 3: Testcontainers Integration Tests
* **Scope:** Full asynchronous flow from WebSocket ingestion to Kafka partition to PostgreSQL persistence.
* **Containers:**
  - `confluentinc/cp-kafka:7.6.0` (KRaft mode, zero ZooKeeper).
  - `postgres:16-alpine`.
  - `redis:7-alpine`.
* **Memory Safety Enforcement:** Tests MUST run with Paranoid Netty leak detection:
  ```bash
  ./mvnw test -Dio.netty.leakDetection.level=PARANOID
  ```

### Tier 4: Load & Stress Testing (Gatling)
* **Scope:** Simulates 10,000 concurrent WebSocket callers bombarding the Voice Gateway.
* **Validation:** Monitors consumer group lag, WebSocket frame drop rate, and CPU/memory stability under load.

---

## 3. Fast Verification Commands

```bash
# Rapid local quality gate (< 15 seconds)
make fast-check

# Full multi-module unit test run
make test

# ArchUnit architecture tests & 600-line ceiling check
make arch-check

# Full CI build & test
make check
```
