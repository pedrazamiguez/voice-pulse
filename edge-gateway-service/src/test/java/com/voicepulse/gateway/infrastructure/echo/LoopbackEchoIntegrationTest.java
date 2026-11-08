package com.voicepulse.gateway.infrastructure.echo;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.shared.model.CallSessionId;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LoopbackEchoIntegrationTest {

    private final int port;
    private final LoopbackAudioEchoAdapter echoAdapter;

    @Autowired
    LoopbackEchoIntegrationTest(@LocalServerPort int port, LoopbackAudioEchoAdapter echoAdapter) {
        this.port = port;
        this.echoAdapter = echoAdapter;
    }

    @Test
    @DisplayName("End-to-end WebSocket connection reflects audio frames and tracks sub-100ms RTT")
    void endToEndAudioEcho_reflectsFramesAndTracksRtt() {
        CallSessionId sessionId = CallSessionId.generate();
        URI uri = URI.create("ws://localhost:" + port + "/ws/calls/" + sessionId + "/audio");
        WebSocketClient client = new ReactorNettyWebSocketClient();

        byte[] sentChunk = new byte[640];
        Arrays.fill(sentChunk, (byte) 0x42);

        List<byte[]> receivedChunks = new ArrayList<>();
        Sinks.Empty<Void> completionSignal = Sinks.empty();

        client.execute(
                        uri,
                        session -> {
                            WebSocketMessage message =
                                    session.binaryMessage(factory -> factory.wrap(sentChunk));

                            Mono<Void> send = session.send(Mono.just(message));
                            Mono<Void> receive =
                                    session.receive()
                                            .filter(
                                                    msg ->
                                                            msg.getType()
                                                                    == WebSocketMessage.Type.BINARY)
                                            .take(1)
                                            .doOnNext(
                                                    msg -> {
                                                        DataBuffer buffer = msg.getPayload();
                                                        byte[] bytes =
                                                                new byte
                                                                        [buffer
                                                                                .readableByteCount()];
                                                        buffer.read(bytes);
                                                        receivedChunks.add(bytes);
                                                    })
                                            .then()
                                            .doFinally(sig -> completionSignal.tryEmitEmpty());

                            return Mono.when(send, receive);
                        })
                .block(Duration.ofSeconds(5));

        completionSignal.asMono().block(Duration.ofSeconds(5));

        assertThat(receivedChunks).hasSize(1);
        assertThat(receivedChunks.getFirst()).isEqualTo(sentChunk);

        EchoLatencyMetrics metrics = echoAdapter.getMetrics(sessionId);
        assertThat(metrics.totalFramesEchoed()).isEqualTo(1L);
        assertThat(metrics.lastRttMs()).isLessThan(100.0);
        assertThat(metrics.sequenceDiscontinuities()).isZero();
    }

    @Test
    @DisplayName("Multiple contiguous PCM chunks are echoed in-order with zero packet drop")
    void multipleFrames_echoedInOrderWithZeroDrop() {
        CallSessionId sessionId = CallSessionId.generate();
        URI uri = URI.create("ws://localhost:" + port + "/ws/calls/" + sessionId + "/audio");
        WebSocketClient client = new ReactorNettyWebSocketClient();

        int frameCount = 10;
        List<byte[]> expectedChunks = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            byte[] frame = new byte[640];
            Arrays.fill(frame, (byte) (i + 1));
            expectedChunks.add(frame);
        }

        List<byte[]> receivedChunks = new ArrayList<>();
        Sinks.Empty<Void> completionSignal = Sinks.empty();

        client.execute(
                        uri,
                        session -> {
                            Flux<WebSocketMessage> outboundFlux =
                                    Flux.fromIterable(expectedChunks)
                                            .delayElements(Duration.ofMillis(10))
                                            .map(
                                                    bytes ->
                                                            session.binaryMessage(
                                                                    factory ->
                                                                            factory.wrap(bytes)));

                            Mono<Void> send = session.send(outboundFlux);
                            Mono<Void> receive =
                                    session.receive()
                                            .filter(
                                                    msg ->
                                                            msg.getType()
                                                                    == WebSocketMessage.Type.BINARY)
                                            .take(frameCount)
                                            .doOnNext(
                                                    msg -> {
                                                        DataBuffer buffer = msg.getPayload();
                                                        byte[] bytes =
                                                                new byte
                                                                        [buffer
                                                                                .readableByteCount()];
                                                        buffer.read(bytes);
                                                        receivedChunks.add(bytes);
                                                    })
                                            .then()
                                            .doFinally(sig -> completionSignal.tryEmitEmpty());

                            return Mono.when(send, receive);
                        })
                .block(Duration.ofSeconds(5));

        completionSignal.asMono().block(Duration.ofSeconds(5));

        assertThat(receivedChunks).hasSize(frameCount);
        for (int i = 0; i < frameCount; i++) {
            assertThat(receivedChunks.get(i)).isEqualTo(expectedChunks.get(i));
        }

        EchoLatencyMetrics metrics = echoAdapter.getMetrics(sessionId);
        assertThat(metrics.totalFramesEchoed()).isEqualTo((long) frameCount);
        assertThat(metrics.sequenceDiscontinuities()).isZero();
        assertThat(metrics.averageRttMs()).isLessThan(100.0);
    }
}
