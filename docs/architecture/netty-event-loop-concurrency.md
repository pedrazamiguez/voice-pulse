# Netty Event-Loop Concurrency & Memory Safety

> [!IMPORTANT]
> **Source of Truth:** Defines Netty WebFlux event-loop rules, Virtual Thread delegation, and zero-leak off-heap `ByteBuf` memory lifecycle management (AGENTS.md §2).

---

## 1. Concurrency Architecture: Event-Loop vs. Virtual Threads

VoicePulse operates two distinct concurrency paradigms:

```
┌────────────────────────────────────────────────────────┐
│            Netty Non-Blocking Event-Loop               │
│  - WebSocket Connection Management                     │
│  - High-frequency Binary Audio Ingestion (16 kHz PCM)  │
│  - Zero Blocking Calls Allowed                         │
└───────────────────────────┬────────────────────────────┘
                            │
                            │ Offload blocking I/O
                            ▼
┌────────────────────────────────────────────────────────┐
│        Java 21 Virtual Threads (Project Loom)          │
│  - PostgreSQL JDBC Queries                             │
│  - Blocking Legacy REST / External Client Calls        │
│  - Executors.newVirtualThreadPerTaskExecutor()         │
└────────────────────────────────────────────────────────┘
```

* **Inviolable Event-Loop Rule:** NEVER block Netty worker threads with synchronous blocking calls, synchronous disk I/O, or `Thread.sleep()`.
* **Virtual Thread Offloading:** When blocking calls cannot be avoided, submit tasks to a Virtual Thread executor:
  ```java
  private final ExecutorService vtExecutor = 
      Executors.newVirtualThreadPerTaskExecutor();

  public CompletableFuture<CustomerDetails> queryLegacyCrm(String pnr) {
      return CompletableFuture.supplyAsync(() -> crmClient.blockingLookup(pnr), vtExecutor);
  }
  ```

---

## 2. Netty `ByteBuf` Lifecycle & Memory Leak Prevention

The Edge Gateway streams binary audio chunks via Netty's pooled off-heap direct memory buffers (`PooledDirectByteBuf`). Direct memory leaks bypass the JVM Garbage Collector and crash containers with `java.lang.OutOfMemoryError: Direct buffer memory`.

### Inviolable Lifecycle Invariant
Every allocated or received `ByteBuf` MUST have its reference count decremented to zero:

```java
public void handleAudioPacket(ByteBuf audioFrame) {
    try {
        if (audioFrame.isReadable()) {
            byte[] pcmData = new byte[audioFrame.readableBytes()];
            audioFrame.readBytes(pcmData);
            kafkaPublisher.publishChunk(pcmData);
        }
    } finally {
        ReferenceCountUtil.release(audioFrame);
    }
}
```

### Downstream Forwarding & Retain Semantics
If a buffer is forwarded down an asynchronous reactive pipeline, call `.retain()` before forwarding and ensure the final subscriber releases it:

```java
public Mono<Void> forwardDownstream(ByteBuf buffer) {
    buffer.retain();
    return reactiveStream.emit(buffer)
        .doFinally(signalType -> ReferenceCountUtil.release(buffer));
}
```

### Automated Leak Detection in CI
All tests in VoicePulse run with Paranoid-level leak detection enabled:
```bash
./mvnw test -Dio.netty.leakDetection.level=PARANOID
```

---

## 3. Reactive Backpressure & Buffer Purging

* **Backpressure:** Downstream Kafka ingestion lag applies backpressure back to Netty channel read handles (`channel.config().setAutoRead(false)`), signaling the client WebSocket to slow down.
* **Instant Barge-in Purging:** Upon receipt of an `InterruptPlaybackEvent`, the Netty outbound channel flushes all pending audio write buffers immediately, stopping voice playback within **< 50 ms**.
