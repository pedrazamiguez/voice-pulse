package com.voicepulse.gateway.infrastructure.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ConcurrentSessionRegistryTest {

    private ConcurrentSessionRegistry registry;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        registry = new ConcurrentSessionRegistry();
        bufferFactory = new DefaultDataBufferFactory();
    }

    @Test
    @DisplayName("Domain SessionRegistryPort methods correctly track CallSession aggregates")
    void domainSessionRegistryPort_tracksCallSessions() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now());

        registry.save(session);

        assertThat(registry.findById(sessionId)).contains(session);
        assertThat(registry.exists(sessionId)).isTrue();
        assertThat(registry.activeSessionCount()).isEqualTo(1);

        registry.remove(sessionId);

        assertThat(registry.findById(sessionId)).isEmpty();
        assertThat(registry.exists(sessionId)).isFalse();
        assertThat(registry.activeSessionCount()).isZero();
    }

    @Test
    @DisplayName("registerSession binds WebSocketSession and creates outbound flux")
    void registerSession_bindsWebSocketSessionAndOutboundFlux() {
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketSession mockSession = mock(WebSocketSession.class);

        Flux<WebSocketMessage> outboundFlux = registry.registerSession(sessionId, mockSession);

        assertThat(registry.getSession(sessionId)).contains(mockSession);
        assertThat(registry.hasWebSocketSession(sessionId)).isTrue();
        assertThat(registry.activeWebSocketSessionCount()).isEqualTo(1);

        WebSocketMessage message =
                new WebSocketMessage(
                        WebSocketMessage.Type.BINARY, bufferFactory.wrap(new byte[] {1, 2, 3}));

        StepVerifier.create(outboundFlux)
                .then(
                        () -> {
                            boolean emitted = registry.emitMessage(sessionId, message);
                            assertThat(emitted).isTrue();
                        })
                .expectNext(message)
                .then(() -> registry.unregisterSession(sessionId))
                .expectComplete()
                .verify(Duration.ofSeconds(2));

        assertThat(registry.getSession(sessionId)).isEmpty();
        assertThat(registry.hasWebSocketSession(sessionId)).isFalse();
        assertThat(registry.activeWebSocketSessionCount()).isZero();
    }

    @Test
    @DisplayName("emitMessage returns false when session is not registered")
    void emitMessage_returnsFalseForUnknownSession() {
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketMessage message =
                new WebSocketMessage(
                        WebSocketMessage.Type.BINARY, bufferFactory.wrap(new byte[] {1, 2, 3}));

        boolean emitted = registry.emitMessage(sessionId, message);

        assertThat(emitted).isFalse();
    }

    @Test
    @DisplayName("remove on SessionRegistryPort unregisters both domain and WebSocket sessions")
    void remove_unregistersBothDomainAndWebSocketSessions() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now());
        WebSocketSession mockSession = mock(WebSocketSession.class);

        registry.save(session);
        registry.registerSession(sessionId, mockSession);

        assertThat(registry.exists(sessionId)).isTrue();
        assertThat(registry.hasWebSocketSession(sessionId)).isTrue();

        registry.remove(sessionId);

        assertThat(registry.exists(sessionId)).isFalse();
        assertThat(registry.hasWebSocketSession(sessionId)).isFalse();
        assertThat(registry.activeSessionCount()).isZero();
        assertThat(registry.activeWebSocketSessionCount()).isZero();
    }

    @Test
    @DisplayName("Null arguments throw NullPointerException")
    void nullArguments_throwException() {
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketSession mockSession = mock(WebSocketSession.class);

        assertThatThrownBy(() -> registry.save(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.findById(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.remove(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.exists(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.registerSession(null, mockSession))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.registerSession(sessionId, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.unregisterSession(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.getSession(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.hasWebSocketSession(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.emitMessage(null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Concurrent registrations and emissions execute thread-safely")
    void concurrentOperations_executeThreadSafely() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<CallSessionId> sessionIds = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            CallSessionId sessionId = CallSessionId.generate();
            sessionIds.add(sessionId);
        }

        for (int i = 0; i < threadCount; i++) {
            CallSessionId id = sessionIds.get(i);
            var unused =
                    executor.submit(
                            () -> {
                                try {
                                    WebSocketSession session = mock(WebSocketSession.class);
                                    Flux<WebSocketMessage> flux =
                                            registry.registerSession(id, session);
                                    flux.subscribe();
                                    WebSocketMessage msg =
                                            new WebSocketMessage(
                                                    WebSocketMessage.Type.BINARY,
                                                    bufferFactory.wrap(new byte[] {0}));
                                    registry.emitMessage(id, msg);
                                } finally {
                                    latch.countDown();
                                }
                            });
        }

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(
                        () ->
                                assertThat(registry.activeWebSocketSessionCount())
                                        .isEqualTo(threadCount));

        for (CallSessionId id : sessionIds) {
            registry.unregisterSession(id);
        }

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(registry.activeWebSocketSessionCount()).isZero());
    }

    @Test
    @DisplayName("Constructors initialize default and custom buffer capacity correctly")
    void constructor_initializesBufferCapacity() {
        ConcurrentSessionRegistry defaultRegistry = new ConcurrentSessionRegistry();
        assertThat(defaultRegistry.getBufferCapacity())
                .isEqualTo(ConcurrentSessionRegistry.DEFAULT_BUFFER_CAPACITY)
                .isEqualTo(8192);

        ConcurrentSessionRegistry customRegistry = new ConcurrentSessionRegistry(4096);
        assertThat(customRegistry.getBufferCapacity()).isEqualTo(4096);

        assertThatThrownBy(() -> new ConcurrentSessionRegistry(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bufferCapacity must be strictly positive");

        assertThatThrownBy(() -> new ConcurrentSessionRegistry(-10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bufferCapacity must be strictly positive");
    }
}
