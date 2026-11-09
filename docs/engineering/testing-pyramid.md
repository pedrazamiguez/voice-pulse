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

## 3. Modern Testing Standards & Tooling

### 3.1 Instancio for Fixture Generation
* **Rule:** Use `org.instancio.Instancio` to generate randomized, fully populated domain models, Kafka event envelopes, and DTOs.
* **Custom Generators:** Apply custom specs for domain invariants (such as 6-character alphanumeric PNRs, monotonic sequence numbers, or valid ISO timestamps):
  ```java
  AirlineDisruptionTicket ticket = Instancio.of(AirlineDisruptionTicket.class)
      .generate(field(AirlineDisruptionTicket::pnr), gen -> gen.string().length(6).alphaNumeric())
      .generate(field(AirlineDisruptionTicket::originalFlightNumber), gen -> gen.oneOf("SW-101", "SW-202"))
      .create();
  ```
* **Benefits:** Eliminates fragile multi-argument constructor calls, detects field drift at compile time, and expands test coverage with randomized inputs.

### 3.2 Idiomatic Mockito for Ports & Adapters
* **Rule:** Unit tests isolating outbound SPI ports or infrastructure adapters MUST use Mockito with JUnit Jupiter:
  ```java
  @ExtendWith(MockitoExtension.class)
  class CallSessionServiceTest {
      @Mock
      private AudioFramePublisherPort publisherPort;

      @Mock
      private SessionRegistryPort registryPort;

      @InjectMocks
      private CallSessionService service;

      @Captor
      private ArgumentCaptor<AudioChunk> chunkCaptor;
  }
  ```
* **Strictness:** Mockito 5 enforces strict stubs. Use `verify(mock).method(...)` for call verification and `lenient().when(...)` only when helper setup methods might not be triggered in all scenarios.
* **Prohibition:** Handwritten manual test doubles (e.g. `InMemorySessionRegistry`, `RecordingAudioFramePublisher`) are prohibited in favor of `@Mock` and `@Captor`.

### 3.3 Awaitility for Asynchronous Verification
* **Rule:** Never use `Thread.sleep()` or fixed polling loops in asynchronous, reactive, or concurrent tests.
* **Usage:** Use `org.awaitility.Awaitility.await()` with explicit timeouts:
  ```java
  await()
      .atMost(Duration.ofSeconds(2))
      .untilAsserted(() -> assertThat(registry.findSession(callId)).isPresent());
  ```

### 3.4 WireMock for Downstream HTTP/REST Contracts
* **Rule:** Downstream HTTP/REST clients (e.g., flight status lookup, CRM integration) MUST be tested against WireMock.
* **Stubbing:** Define deterministic JSON request matching and response bodies using `wiremock-standalone`.

### 3.5 Import Hygiene & Zero Inline FQNs in Tests
* **Rule:** Test files are subject to the same strict import hygiene as production code. Wildcard imports (`import org.junit.jupiter.api.*;`) and inline fully qualified names (`java.util.UUID.randomUUID()`) are strictly forbidden and enforced via ArchUnit.

---

## 4. Fast Verification Commands

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
