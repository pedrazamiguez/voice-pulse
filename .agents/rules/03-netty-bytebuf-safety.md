# Rule 03: Netty ByteBuf Allocation & Memory Safety

## Context & Purpose
In VoicePulse, incoming audio streams are received as high-frequency binary frames over WebSocket connections in `edge-gateway-service`. Netty utilizes reference-counted off-heap direct memory buffers (`ByteBuf`) for zero-copy performance. Failure to properly manage reference counts leads to fatal JVM direct memory exhaustion (OOM).

---

## 1. Reference Counting Fundamentals

* Every Netty `ByteBuf` is reference-counted starting at `1`.
* When a component is done reading or handling a `ByteBuf`, its reference count must be decremented to `0` via `ReferenceCountUtil.release(byteBuf)` or `byteBuf.release()`.
* If a `ByteBuf` is passed downstream to an asynchronous consumer, it must either:
  1. Be explicitly retained: `byteBuf.retain()` (refCount $\rightarrow 2$), and released by the downstream consumer once finished.
  2. Be sliced or deep-copied into a Java heap byte array (`byte[]`) if transferred out of the Netty event loop into domain records.

---

## 2. Inbound WebSocket Handler Lifecycle

When receiving WebSocket frames in a Spring WebFlux `WebSocketHandler`:

```java
@Override
public Mono<Void> handle(WebSocketSession session) {
    return session.receive()
        .doOnNext(message -> {
            if (message.getType() == WebSocketMessage.Type.BINARY) {
                DataBuffer dataBuffer = message.getPayload();
                try {
                    // Convert or copy payload
                    byte[] audioBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(audioBytes);
                    // Dispatch pure domain/kafka event
                } finally {
                    DataBufferUtils.release(dataBuffer);
                }
            }
        })
        .then();
}
```

* **Rule:** Always wrap buffer ingestion in `try-finally` blocks to guarantee release even when exceptions occur.

---

## 3. ByteBuf Zero-Leak Testing

* In all unit and integration tests touching Netty or WebFlux pipelines:
  - Configure Netty leak detection level to `PARANOID`:
    ```bash
    -Dio.netty.leakDetection.level=PARANOID
    ```
* If ArchUnit or test execution detects unreleased buffers in Netty's resource leak tracker, the build must fail immediately.
* Do not suppress Netty leak warnings. Identify and release the leaking allocation.
