package com.voicepulse.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.voicepulse.gateway.infrastructure.echo.EchoLatencyMetrics;
import com.voicepulse.gateway.infrastructure.echo.LoopbackAudioEchoAdapter;
import com.voicepulse.gateway.infrastructure.registry.ConcurrentSessionRegistry;
import com.voicepulse.shared.model.CallSessionId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ResourceLeakDetector;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * End-to-end integration test suite verifying real-time WebSocket audio streaming, loopback echo
 * fidelity, monotonic frame sequencing, sub-100ms latency SLA, and Netty ByteBuf memory safety
 * under {@code -Dio.netty.leakDetection.level=PARANOID}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AudioEchoIntegrationTest {

    private static final int TOTAL_FRAMES = 5000;
    private static final int FRAME_SIZE_BYTES = 640; // 20ms @ 16 kHz 16-bit mono PCM

    private static ListAppender<ILoggingEvent> logAppender;
    private static Logger leakDetectorLogger;
    private static Logger rootLogger;

    private final int port;
    private final LoopbackAudioEchoAdapter echoAdapter;
    private final ConcurrentSessionRegistry sessionRegistry;

    @Autowired
    AudioEchoIntegrationTest(
            @LocalServerPort int port,
            LoopbackAudioEchoAdapter echoAdapter,
            ConcurrentSessionRegistry sessionRegistry) {
        this.port = port;
        this.echoAdapter = echoAdapter;
        this.sessionRegistry = sessionRegistry;
    }

    @BeforeAll
    static void configureNettyLeakDetection() {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        System.setProperty("io.netty.leakDetection.level", "PARANOID");

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logAppender = new ListAppender<>();
        logAppender.setContext(context);
        logAppender.start();

        leakDetectorLogger = context.getLogger(ResourceLeakDetector.class);
        leakDetectorLogger.addAppender(logAppender);

        rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logAppender);
    }

    @AfterAll
    static void tearDownLeakDetector() {
        if (leakDetectorLogger != null && logAppender != null) {
            leakDetectorLogger.detachAppender(logAppender);
        }
        if (rootLogger != null && logAppender != null) {
            rootLogger.detachAppender(logAppender);
        }
    }

    @BeforeEach
    void clearLogEvents() {
        if (logAppender != null) {
            logAppender.list.clear();
        }
    }

    @AfterEach
    void assertZeroNettyMemoryLeaks() {
        System.gc();

        ByteBuf triggerBuf = Unpooled.buffer(1);
        triggerBuf.release();

        List<String> leakErrors =
                logAppender.list.stream()
                        .filter(
                                event ->
                                        event.getMessage().contains("LEAK:")
                                                || event.getFormattedMessage().contains("LEAK:")
                                                || (event.getLevel() == Level.ERROR
                                                        && event.getLoggerName()
                                                                .contains("ResourceLeakDetector")))
                        .map(ILoggingEvent::getFormattedMessage)
                        .toList();

        assertThat(leakErrors)
                .as("Zero Netty ByteBuf memory leaks permitted under PARANOID level")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "Streams 5,000 synthetic frames end-to-end with zero frame loss, in-order sequencing,"
                    + " and sub-100ms latency")
    void stream5000SyntheticAudioFrames_completesWithZeroLossAndSub100msLatency() {
        CallSessionId sessionId = CallSessionId.generate();
        URI uri = URI.create("ws://localhost:" + port + "/ws/calls/" + sessionId + "/audio");
        WebSocketClient client = new ReactorNettyWebSocketClient();

        List<byte[]> expectedFrames = new ArrayList<>(TOTAL_FRAMES);
        for (int i = 0; i < TOTAL_FRAMES; i++) {
            expectedFrames.add(generateSyntheticFrame(i));
        }

        List<TimedFrame> receivedFrames =
                Collections.synchronizedList(new ArrayList<>(TOTAL_FRAMES));
        long[] sendTimestamps = new long[TOTAL_FRAMES];
        Sinks.Empty<Void> completionSignal = Sinks.empty();

        client.execute(
                        uri,
                        session -> {
                            Flux<WebSocketMessage> outboundFlux =
                                    Flux.range(0, TOTAL_FRAMES)
                                            .delayElements(Duration.ofNanos(500_000))
                                            .map(
                                                    index -> {
                                                        byte[] payload = expectedFrames.get(index);
                                                        sendTimestamps[index] = System.nanoTime();
                                                        return session.binaryMessage(
                                                                factory -> factory.wrap(payload));
                                                    });

                            Mono<Void> send = session.send(outboundFlux);

                            Mono<Void> receive =
                                    session.receive()
                                            .filter(
                                                    msg ->
                                                            msg.getType()
                                                                    == WebSocketMessage.Type.BINARY)
                                            .take(TOTAL_FRAMES)
                                            .doOnNext(
                                                    msg -> {
                                                        DataBuffer buffer = msg.getPayload();
                                                        byte[] bytes =
                                                                new byte
                                                                        [buffer
                                                                                .readableByteCount()];
                                                        buffer.read(bytes);
                                                        long receiveTime = System.nanoTime();
                                                        receivedFrames.add(
                                                                new TimedFrame(bytes, receiveTime));
                                                    })
                                            .then()
                                            .doFinally(sig -> completionSignal.tryEmitEmpty());

                            return Mono.when(send, receive);
                        })
                .block(Duration.ofSeconds(60));

        completionSignal.asMono().block(Duration.ofSeconds(10));

        // 1. Zero Frame Loss Assertion
        assertThat(receivedFrames)
                .as("All %d synthetic frames must be received back through echo", TOTAL_FRAMES)
                .hasSize(TOTAL_FRAMES);

        // 2. Monotonic Sequence Continuity and Byte Integrity Assertions
        List<Double> clientRttMsList = new ArrayList<>(TOTAL_FRAMES);
        for (int i = 0; i < TOTAL_FRAMES; i++) {
            TimedFrame timedFrame = receivedFrames.get(i);
            byte[] receivedBytes = timedFrame.payload();
            byte[] expectedBytes = expectedFrames.get(i);

            long receivedSeq = ByteBuffer.wrap(receivedBytes, 0, 8).getLong();
            assertThat(receivedSeq)
                    .as("Frame at position %d must match sent sequence number %d", i, i)
                    .isEqualTo((long) i);

            assertThat(receivedBytes)
                    .as("Payload byte integrity corrupted at sequence %d", i)
                    .isEqualTo(expectedBytes);

            long sendTime = sendTimestamps[i];
            long rttNanos = Math.max(0L, timedFrame.receiveTimeNanos() - sendTime);
            clientRttMsList.add(rttNanos / 1_000_000.0);
        }

        // 3. Telemetry Metrics Verification (Server-Side)
        EchoLatencyMetrics serverMetrics = echoAdapter.getMetrics(sessionId);
        assertThat(serverMetrics.totalFramesEchoed())
                .as("Total echoed frames tracked by server")
                .isEqualTo((long) TOTAL_FRAMES);

        assertThat(serverMetrics.sequenceDiscontinuities())
                .as("Zero sequence discontinuities expected in server echo tracker")
                .isZero();

        assertThat(serverMetrics.averageRttMs())
                .as("Server average RTT must satisfy sub-100ms SLA ceiling")
                .isLessThan(100.0);

        // 4. Latency SLA Verification (Client-Side)
        double clientAverageRttMs =
                clientRttMsList.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        assertThat(clientAverageRttMs)
                .as("Client-side measured average RTT must satisfy sub-100ms SLA ceiling")
                .isLessThan(100.0);

        // 5. Session Lifecycle Cleanup Verification
        assertThat(sessionRegistry.hasWebSocketSession(sessionId))
                .as("WebSocket session must be unregistered upon stream completion")
                .isFalse();
    }

    @Test
    @DisplayName(
            "Consecutive call sessions stream independently and unregister without memory leaks")
    void consecutiveCallSessions_streamIndependentlyAndUnregisterWithoutLeaks() {
        int sessionFrames = 100;
        for (int s = 0; s < 2; s++) {
            CallSessionId sessionId = CallSessionId.generate();
            URI uri = URI.create("ws://localhost:" + port + "/ws/calls/" + sessionId + "/audio");
            WebSocketClient client = new ReactorNettyWebSocketClient();

            List<byte[]> expected = new ArrayList<>(sessionFrames);
            for (int i = 0; i < sessionFrames; i++) {
                expected.add(generateSyntheticFrame(i));
            }

            List<byte[]> received = Collections.synchronizedList(new ArrayList<>(sessionFrames));
            Sinks.Empty<Void> completionSignal = Sinks.empty();

            client.execute(
                            uri,
                            session -> {
                                Flux<WebSocketMessage> outboundFlux =
                                        Flux.range(0, sessionFrames)
                                                .delayElements(Duration.ofNanos(500_000))
                                                .map(
                                                        index ->
                                                                session.binaryMessage(
                                                                        factory ->
                                                                                factory.wrap(
                                                                                        expected
                                                                                                .get(
                                                                                                        index))));

                                Mono<Void> send = session.send(outboundFlux);
                                Mono<Void> recv =
                                        session.receive()
                                                .filter(
                                                        msg ->
                                                                msg.getType()
                                                                        == WebSocketMessage.Type
                                                                                .BINARY)
                                                .take(sessionFrames)
                                                .doOnNext(
                                                        msg -> {
                                                            DataBuffer buffer = msg.getPayload();
                                                            byte[] bytes =
                                                                    new byte
                                                                            [buffer
                                                                                    .readableByteCount()];
                                                            buffer.read(bytes);
                                                            received.add(bytes);
                                                        })
                                                .then()
                                                .doFinally(sig -> completionSignal.tryEmitEmpty());

                                return Mono.when(send, recv);
                            })
                    .block(Duration.ofSeconds(10));

            completionSignal.asMono().block(Duration.ofSeconds(5));

            assertThat(received).hasSize(sessionFrames);
            for (int i = 0; i < sessionFrames; i++) {
                assertThat(received.get(i)).isEqualTo(expected.get(i));
            }

            EchoLatencyMetrics metrics = echoAdapter.getMetrics(sessionId);
            assertThat(metrics.totalFramesEchoed()).isEqualTo((long) sessionFrames);
            assertThat(metrics.sequenceDiscontinuities()).isZero();
            assertThat(sessionRegistry.hasWebSocketSession(sessionId)).isFalse();
        }
    }

    /**
     * Generates a deterministic 640-byte PCM frame for the given sequence index. Bytes 0..7 store
     * the sequence number as big-endian long. Bytes 8..639 store a deterministic byte pattern based
     * on the sequence index.
     */
    private byte[] generateSyntheticFrame(int index) {
        byte[] frame = new byte[FRAME_SIZE_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        buffer.putLong((long) index);

        for (int b = 8; b < FRAME_SIZE_BYTES; b++) {
            frame[b] = (byte) ((index * 31 + b) & 0xFF);
        }
        return frame;
    }

    private record TimedFrame(byte[] payload, long receiveTimeNanos) {}
}
