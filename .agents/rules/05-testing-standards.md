# Rule 05: Modern Testing Standards & Tooling

## Context & Purpose
VoicePulse enforces rigorous testing standards across domain models, reactive streams, event envelopes, and external adapters. Tests must be fast, deterministic, maintainable, and free of copy-pasted fixtures or manual test doubles.

---

## 1. Instancio Fixtures for Domain Models, Envelopes & DTOs

* **Mandate:** Tests MUST use Instancio (`org.instancio.Instancio`) to instantiate complex domain models, event records, and transport DTOs.
* **Prohibition of Multi-Argument Constructors:** Never write multi-argument constructors with hardcoded dummy strings (e.g. `new AirlineDisruptionTicket("John", "ABC123", ...)`). Field additions or schema evolutions break manual constructors; Instancio automatically fills non-constrained fields.
* **Custom Generator Specifications:** When testing domain invariants, customize only the relevant fields using `generate(field(...), gen -> ...)`:
  ```java
  AirlineDisruptionTicket ticket = Instancio.of(AirlineDisruptionTicket.class)
      .generate(field(AirlineDisruptionTicket::pnr), gen -> gen.string().length(6).alphaNumeric())
      .generate(field(AirlineDisruptionTicket::originalFlightNumber), gen -> gen.oneOf("SW-101", "SW-202"))
      .create();
  ```

---

## 2. Idiomatic Mockito for Ports & Adapters

* **Mandate:** Secondary SPI outbound ports and infrastructure dependencies in unit tests MUST be mocked using Mockito with JUnit Jupiter:
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
* **Prohibition of Manual Test Doubles:** Never maintain ad-hoc in-memory fakes or recording classes (e.g., `InMemorySessionRegistry`, `RecordingAudioFramePublisher`). Use `@Mock`, `@InjectMocks`, and `@Captor ArgumentCaptor<T>` with `verify()`.
* **Strict Stubbing:** Mockito 5 enforces strict stub verification. Unused stubs fail the test. When shared setup helpers contain stubs that are only exercised by certain tests, annotate them explicitly with `lenient().when(...)`.

---

## 3. Deterministic Asynchronous Verification with Awaitility

* **Prohibition of `Thread.sleep()`:** Never use `Thread.sleep()` or fixed polling loops in tests. Fixed delays introduce race conditions, flakiness, and unnecessarily slow down CI pipelines.
* **Mandate:** Use Awaitility (`org.awaitility.Awaitility.await()`) with descriptive timeouts for reactive, concurrent, or asynchronous state changes:
  ```java
  await()
      .atMost(Duration.ofSeconds(2))
      .untilAsserted(() -> assertThat(registry.findSession(callId)).isPresent());
  ```

---

## 4. WireMock for Downstream HTTP/REST Services

* External HTTP integrations (CRM, flight lookup, MCP endpoints) must be verified against WireMock (`wiremock-standalone`).
* Use declarative URL and header matching with deterministic canned JSON payloads.

---

## 5. Import Hygiene & Zero Inline Fully Qualified Names

* **No Wildcard Imports:** Test files MUST NOT use wildcard imports (`import org.junit.jupiter.api.*;`, `import static org.mockito.Mockito.*;` is tolerated only for static Mockito imports when granular imports become excessively verbose, but class-level wildcards like `import java.util.*;` are strictly prohibited).
* **Zero Inline FQNs:** Never use inline fully qualified class names anywhere in test code (e.g., `java.util.UUID.randomUUID()`, `java.time.Instant.now()`, `java.util.List<String>`). All types must be imported at the top of the test file.
* **ArchUnit Enforcement:** Automated ArchUnit rules (`noWildcardImports` and `noInlineFullyQualifiedNames`) scan both `src/main/java` and `src/test/java` across all reactor modules and fail the build if violated.
