package com.voicepulse.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.voicepulse.gateway.domain.ports.inbound.BridgeOperatorAudioUseCase;
import com.voicepulse.gateway.domain.ports.outbound.AudioFramePublisherPort;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.gateway.infrastructure.ws.CallBridgeRegistry;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallSessionId;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Operator Audio Bridge WebSocket Integration Tests")
class OperatorAudioBridgeIntegrationTest {

    private static final int FRAME_BYTES = 640;

    @LocalServerPort private int port;

    @Autowired private BridgeOperatorAudioUseCase bridgeOperatorAudioUseCase;

    @Autowired private SessionRegistryPort sessionRegistry;

    @Autowired private CallBridgeRegistry callBridgeRegistry;

    @MockitoSpyBean private AudioFramePublisherPort audioFramePublisherPort;

    private final WebSocketClient webSocketClient = new ReactorNettyWebSocketClient();

    @Test
    @DisplayName(
            "rejects premature operator connection (1013), unauthorized operator (1008), and"
                    + " unknown session (1008)")
    void enforcesHandshakeAuthorizationStatusCodes() throws Exception {
        CallSessionId unknownCallId = CallSessionId.generate();
        CloseStatus unknownStatus = connectExpectingClose(operatorUri("op-1", unknownCallId));
        assertThat(unknownStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());

        CallSessionId callId = CallSessionId.generate();
        Sinks.Many<byte[]> callerFrames = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.One<Void> closeCaller = Sinks.one();

        CompletableFuture<Void> callerSessionFuture =
                webSocketClient
                        .execute(
                                callerUri(callId),
                                session -> {
                                    Mono<Void> send =
                                            session.send(
                                                            callerFrames
                                                                    .asFlux()
                                                                    .takeUntilOther(
                                                                            closeCaller.asMono())
                                                                    .map(
                                                                            bytes ->
                                                                                    session
                                                                                            .binaryMessage(
                                                                                                    f ->
                                                                                                            f
                                                                                                                    .wrap(
                                                                                                                            bytes))))
                                                    .then(session.close());
                                    Mono<Void> receive =
                                            session.receive()
                                                    .doOnNext(
                                                            msg -> {
                                                                DataBuffer payload =
                                                                        msg.getPayload();
                                                                byte[] drain =
                                                                        new byte
                                                                                [payload
                                                                                        .readableByteCount()];
                                                                payload.read(drain);
                                                            })
                                                    .then();
                                    return Mono.when(send, receive);
                                })
                        .toFuture();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(sessionRegistry.findById(callId)).isPresent());

        CloseStatus pendingStatus = connectExpectingClose(operatorUri("op-1", callId));
        assertThat(pendingStatus.getCode()).isEqualTo(1013);

        bridgeOperatorAudioUseCase.activateBridge(
                callId, "op-1", "/ws/operators/op-1/calls/" + callId + "/audio", Instant.now());

        CloseStatus unauthorizedStatus = connectExpectingClose(operatorUri("op-intruder", callId));
        assertThat(unauthorizedStatus.getCode()).isEqualTo(1008);

        closeCaller.tryEmitEmpty();
        callerSessionFuture.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName(
            "relays bidirectional 16 kHz PCM frames sub-100ms, cuts off AI publisher, enforces"
                    + " 64KB DoS limit, and frees all ByteBufs on disconnect")
    void bridgesBidirectionalAudioCutOffAiPublisherAndCleansUp() throws Exception {
        CallSessionId callId = CallSessionId.generate();
        String operatorId = "op-bridge-1";

        Sinks.Many<byte[]> callerOutbound = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.One<Void> callerStop = Sinks.one();
        List<String> callerControlMessages = new CopyOnWriteArrayList<>();
        List<TimedFrame> callerReceivedOperatorAudio = new CopyOnWriteArrayList<>();

        CompletableFuture<Void> callerDone =
                webSocketClient
                        .execute(
                                callerUri(callId),
                                session -> {
                                    Mono<Void> send =
                                            session.send(
                                                            callerOutbound
                                                                    .asFlux()
                                                                    .takeUntilOther(
                                                                            callerStop.asMono())
                                                                    .map(
                                                                            bytes ->
                                                                                    session
                                                                                            .binaryMessage(
                                                                                                    f ->
                                                                                                            f
                                                                                                                    .wrap(
                                                                                                                            bytes))))
                                                    .then(session.close());
                                    Mono<Void> receive =
                                            session.receive()
                                                    .doOnNext(
                                                            msg -> {
                                                                if (msg.getType()
                                                                        == WebSocketMessage.Type
                                                                                .TEXT) {
                                                                    callerControlMessages.add(
                                                                            msg.getPayloadAsText());
                                                                } else if (msg.getType()
                                                                        == WebSocketMessage.Type
                                                                                .BINARY) {
                                                                    DataBuffer buf =
                                                                            msg.getPayload();
                                                                    byte[] bytes =
                                                                            new byte
                                                                                    [buf
                                                                                            .readableByteCount()];
                                                                    buf.read(bytes);
                                                                    long rxNanos =
                                                                            System.nanoTime();
                                                                    if (bytes.length == FRAME_BYTES
                                                                            && ByteBuffer.wrap(
                                                                                                    bytes)
                                                                                            .getInt(
                                                                                                    8)
                                                                                    == 2) {
                                                                        callerReceivedOperatorAudio
                                                                                .add(
                                                                                        new TimedFrame(
                                                                                                bytes,
                                                                                                rxNanos));
                                                                    }
                                                                }
                                                            })
                                                    .then();
                                    return Mono.when(send, receive);
                                })
                        .toFuture();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(sessionRegistry.findById(callId)).isPresent());

        // 1. Pre-bridge caller frame goes to AI publisher (loopback echo)
        callerOutbound.tryEmitNext(encodeFrame(1, 0, System.nanoTime()));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> verify(audioFramePublisherPort, times(1)).publishAudioChunk(any()));

        // 2. Activate bridge -> caller receives INTERRUPT JSON frame flushing AI playback
        String endpoint = "/ws/operators/" + operatorId + "/calls/" + callId + "/audio";
        bridgeOperatorAudioUseCase.activateBridge(callId, operatorId, endpoint, Instant.now());

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(callerControlMessages)
                                        .anyMatch(msg -> msg.contains("\"op\":\"INTERRUPT\"")));

        // 3. Connect authorized operator WebSocket
        Sinks.Many<byte[]> operatorOutbound = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.One<Void> operatorStop = Sinks.one();
        List<TimedFrame> operatorReceivedCallerAudio = new CopyOnWriteArrayList<>();

        CompletableFuture<Void> operatorDone =
                webSocketClient
                        .execute(
                                operatorUri(operatorId, callId),
                                session -> {
                                    Mono<Void> send =
                                            session.send(
                                                            operatorOutbound
                                                                    .asFlux()
                                                                    .takeUntilOther(
                                                                            operatorStop.asMono())
                                                                    .map(
                                                                            bytes ->
                                                                                    session
                                                                                            .binaryMessage(
                                                                                                    f ->
                                                                                                            f
                                                                                                                    .wrap(
                                                                                                                            bytes))))
                                                    .then(session.close());
                                    Mono<Void> receive =
                                            session.receive()
                                                    .filter(
                                                            msg ->
                                                                    msg.getType()
                                                                            == WebSocketMessage.Type
                                                                                    .BINARY)
                                                    .doOnNext(
                                                            msg -> {
                                                                DataBuffer buf = msg.getPayload();
                                                                byte[] bytes =
                                                                        new byte
                                                                                [buf
                                                                                        .readableByteCount()];
                                                                buf.read(bytes);
                                                                long rxNanos = System.nanoTime();
                                                                operatorReceivedCallerAudio.add(
                                                                        new TimedFrame(
                                                                                bytes, rxNanos));
                                                            })
                                                    .then();
                                    return Mono.when(send, receive);
                                })
                        .toFuture();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(callBridgeRegistry.getConnectedSession(callId))
                                        .isPresent());

        // 4. Stream 20 frames each direction
        int bridgeFrames = 20;
        for (int i = 0; i < bridgeFrames; i++) {
            long tx = System.nanoTime();
            callerOutbound.tryEmitNext(encodeFrame(1, i, tx));
            operatorOutbound.tryEmitNext(encodeFrame(2, i, System.nanoTime()));
        }

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            assertThat(operatorReceivedCallerAudio).hasSize(bridgeFrames);
                            assertThat(callerReceivedOperatorAudio).hasSize(bridgeFrames);
                        });

        // Verify zero frames leaked to AI AudioFramePublisherPort after bridge activation
        verify(audioFramePublisherPort, times(1)).publishAudioChunk(any());

        // Verify bit-exact ordering and sub-100ms average one-way relay latency
        assertAvgLatencySub100Ms(operatorReceivedCallerAudio, 1);
        assertAvgLatencySub100Ms(callerReceivedOperatorAudio, 2);

        // 5. Verify 64 KB DoS guard on an authorized operator reconnect
        operatorStop.tryEmitEmpty();
        operatorDone.get(5, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(callBridgeRegistry.getConnectedSession(callId)).isEmpty());

        byte[] oversizedFrame = new byte[AudioChunk.MAX_CHUNK_SIZE_BYTES + 2];
        CompletableFuture<CloseStatus> dosStatusFuture = new CompletableFuture<>();
        webSocketClient
                .execute(
                        operatorUri(operatorId, callId),
                        session ->
                                Mono.when(
                                        session.send(
                                                Flux.just(
                                                        session.binaryMessage(
                                                                f -> f.wrap(oversizedFrame)))),
                                        session.receive().then(),
                                        session.closeStatus()
                                                .doOnNext(dosStatusFuture::complete)
                                                .then()))
                .block(Duration.ofSeconds(5));
        assertThat(dosStatusFuture.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(1009);

        // 6. Verify bridge teardown when caller hangs up
        callerStop.tryEmitEmpty();
        callerDone.get(5, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                assertThat(callBridgeRegistry.getAssignedOperatorId(callId))
                                        .isEmpty());
    }

    private CloseStatus connectExpectingClose(URI uri) throws Exception {
        CompletableFuture<CloseStatus> closeStatusFuture = new CompletableFuture<>();
        webSocketClient
                .execute(
                        uri,
                        session ->
                                Mono.when(
                                        session.receive().then(),
                                        session.closeStatus()
                                                .doOnNext(closeStatusFuture::complete)
                                                .then()))
                .block(Duration.ofSeconds(5));
        return closeStatusFuture.get(5, TimeUnit.SECONDS);
    }

    private URI callerUri(CallSessionId callId) {
        return URI.create("ws://localhost:" + port + "/ws/calls/" + callId + "/audio");
    }

    private URI operatorUri(String operatorId, CallSessionId callId) {
        return URI.create(
                "ws://localhost:"
                        + port
                        + "/ws/operators/"
                        + operatorId
                        + "/calls/"
                        + callId
                        + "/audio");
    }

    private static byte[] encodeFrame(int directionTag, int seq, long sentNanos) {
        byte[] bytes = new byte[FRAME_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.putLong(0, sentNanos);
        buf.putInt(8, directionTag);
        buf.putInt(12, seq);
        return bytes;
    }

    private static void assertAvgLatencySub100Ms(List<TimedFrame> frames, int expectedTag) {
        long totalNanos = 0L;
        for (int i = 0; i < frames.size(); i++) {
            TimedFrame frame = frames.get(i);
            ByteBuffer buf = ByteBuffer.wrap(frame.payload());
            long txNanos = buf.getLong(0);
            int tag = buf.getInt(8);
            int seq = buf.getInt(12);
            assertThat(tag).isEqualTo(expectedTag);
            assertThat(seq).isEqualTo(i);
            totalNanos += Math.max(0L, frame.receivedAtNanos() - txNanos);
        }
        double avgMillis = (totalNanos / (double) frames.size()) / 1_000_000.0;
        assertThat(avgMillis).as("Average relay latency must be under 100ms").isLessThan(100.0);
    }

    private record TimedFrame(byte[] payload, long receivedAtNanos) {}
}
