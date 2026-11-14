# Rule 01: Hexagonal Architecture (Ports & Adapters)

## Context & Purpose
VoicePulse enforces Hexagonal Architecture across all services (`conversation-engine`, `edge-gateway-service`, `routing-handoff-service`) to keep core voice and business domain models decoupled from frameworks, transport protocols, and storage drivers.

---

## 1. Domain Core Purity

* Packages matching `..domain..` (e.g. `com.voicepulse.conversation.domain.*`) represent the enterprise business core.
* **Inviolable Rule:** Domain classes must contain ZERO external dependencies, annotations, or imports:
  - ❌ **Forbidden:**
    - `org.springframework.*`
    - `io.netty.*`
    - `org.apache.kafka.*`
    - `jakarta.persistence.*` / `javax.persistence.*`
    - `com.fasterxml.jackson.*`
    - `reactor.core.*` (keep Reactor in adapters and use standard CompletableFuture or pure synchronous domain returns where possible)
    - `lombok.*` (No Lombok annotations; domain relies solely on pure Java 21 records and standard Java constructors)
    - `org.mapstruct.*` (No MapStruct annotations; mappers reside in `infrastructure.<adapter>.mapper`)
  - ✅ **Permitted:**
    - `java.lang.*`, `java.util.*`, `java.time.*`, `java.math.*`
    - Pure records, value objects, domain entities, and domain exceptions.

---

## 2. Port Definitions & Package Structure

* All ports must reside in dedicated packages:
  - `com.voicepulse.<module>.domain.ports.inbound`
  - `com.voicepulse.<module>.domain.ports.outbound`
* Ports MUST be declared as Java `public interface`.

### Inbound Ports (Driving Ports / Use Cases)
* Define operations that driving adapters (WebSocket handler, Kafka listener, REST controller) can invoke on the domain core.
* **Naming:** Suffix with `UseCase` (e.g., `ProcessAudioChunkUseCase`, `EvaluateBargeInUseCase`, `SubmitDisruptionTicketUseCase`).

```java
package com.voicepulse.conversation.domain.ports.inbound;

import com.voicepulse.conversation.domain.model.AudioFrame;
import com.voicepulse.conversation.domain.model.CallSessionId;

public interface ProcessAudioChunkUseCase {
    void processAudioChunk(CallSessionId callId, AudioFrame frame);
}
```

### Outbound Ports (Driven Ports / Secondary SPIs)
* Define contracts required by domain logic that external infrastructure adapters must satisfy.
* **Naming:** Suffix with `Port` (e.g., `TicketRepositoryPort`, `AudioFramePublisherPort`, `OperatorQueuePort`).

```java
package com.voicepulse.conversation.domain.ports.outbound;

import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;

public interface TicketRepositoryPort {
    void saveTicket(AirlineDisruptionTicket ticket);
}
```

---

## 3. Adapters & Direction of Dependencies

* Primary adapters depend on Inbound Ports to trigger domain actions.
* Secondary adapters implement Outbound Ports to fulfill domain I/O needs.
* **Dependency Rule:** Infrastructure $\rightarrow$ Domain. The domain package NEVER imports or references any class in `infrastructure.*` or `application.*`.
* **Object Transformation:** Transform transport DTOs to/from pure domain records using MapStruct mappers residing strictly in `infrastructure.<adapter>.mapper` with `unmappedTargetPolicy = ReportingPolicy.ERROR`. Inline `new ObjectMapper()` and reflection-based conversions are strictly forbidden.
* All wiring is performed via Constructor Injection. In infrastructure adapters, configuration classes, and application services, use Lombok `@RequiredArgsConstructor` (with `@NonNull` on required fields) and `@Slf4j` to eliminate boilerplate. Field injection (`@Autowired`) is strictly prohibited. Lombok annotations are strictly forbidden in `domain..` classes.

---

## 4. Fallback Beans & Null Object Pattern
* When bridging incomplete domain services or providing no-op fallbacks for inbound ports, NEVER emit anonymous lambdas inside `@Configuration` classes.
* Implement a formal top-level class using the GoF Null Object Pattern (e.g. `NoOpProcessAudioChunkUseCase`), annotated with Spring's `@Fallback` (or `@ConditionalOnMissingBean`) and `@Component`, located in an infrastructure package (`infrastructure.fallback..` or `infrastructure.<adapter>..`).
* When the actual domain service is implemented, Spring automatically supersedes the `@Fallback` bean with zero wiring changes.

---

## 5. Configuration Properties & Fail-Fast Standards
* Declare configuration properties as Java `record` with `@ConfigurationProperties(prefix = "...")`.
* Default values belong exclusively in `application.yml` via `${ENV_VAR:fallback}` expressions. NEVER use `@DefaultValue("...")` annotations or declare redundant `public static final` constants.
* Compact constructors MUST fail fast (e.g. `if (workerPoolSize <= 0) throw new IllegalArgumentException(...)`). Never silently swallow invalid values with hardcoded defaults.
