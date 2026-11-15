package com.voicepulse.routing.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.routing.domain.model.OperatorAvailabilityStatus;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.inbound.InspectRoutingQueueUseCase;
import com.voicepulse.routing.domain.ports.inbound.ManageOperatorPresenceUseCase;
import com.voicepulse.routing.infrastructure.ws.config.OperatorConsoleProperties;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorAssignmentPayloadDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorWsMessage;
import com.voicepulse.routing.infrastructure.ws.mapper.OperatorConsoleMapper;
import com.voicepulse.routing.infrastructure.ws.mapper.TranscriptContextExtractor;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import com.voicepulse.shared.model.CallId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.instancio.Instancio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
@DisplayName("OperatorConsoleWebSocketHandler Tests")
class OperatorConsoleWebSocketHandlerTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:15:00Z");

    @Mock private ManageOperatorPresenceUseCase manageOperatorPresenceUseCase;
    @Mock private InspectRoutingQueueUseCase inspectRoutingQueueUseCase;

    private OperatorConsoleSessionRegistry sessionRegistry;
    private VirtualThreadTaskOffloader taskOffloader;
    private ObjectMapper objectMapper;
    private OperatorConsoleWebSocketHandler handler;
    private NettyDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        OperatorConsoleProperties props =
                new OperatorConsoleProperties("ws://localhost:8080", Duration.ofSeconds(10), 64);
        sessionRegistry = new OperatorConsoleSessionRegistry(props);
        taskOffloader = new VirtualThreadTaskOffloader();
        objectMapper = KafkaJsonCodecProvider.objectMapper();
        OperatorConsoleMapper mapper = Mappers.getMapper(OperatorConsoleMapper.class);
        TranscriptContextExtractor extractor = new TranscriptContextExtractor();
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

        handler =
                new OperatorConsoleWebSocketHandler(
                        new OperatorConsoleUriParser(),
                        sessionRegistry,
                        manageOperatorPresenceUseCase,
                        inspectRoutingQueueUseCase,
                        mapper,
                        extractor,
                        taskOffloader,
                        objectMapper,
                        fixedClock);
        bufferFactory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        taskOffloader.destroy();
    }

    private WebSocketMessage textMessage(String json, ByteBuf[] capturedBuf) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(bytes.length);
        buf.writeBytes(bytes);
        if (capturedBuf != null) {
            capturedBuf[0] = buf;
        }
        NettyDataBuffer dataBuffer = bufferFactory.wrap(buf);
        return new WebSocketMessage(WebSocketMessage.Type.TEXT, dataBuffer);
    }

    private WebSocketMessage binaryMessage(byte[] bytes, ByteBuf[] capturedBuf) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(bytes.length);
        buf.writeBytes(bytes);
        if (capturedBuf != null) {
            capturedBuf[0] = buf;
        }
        NettyDataBuffer dataBuffer = bufferFactory.wrap(buf);
        return new WebSocketMessage(WebSocketMessage.Type.BINARY, dataBuffer);
    }

    private WebSocketSession mockSession(
            String path, Flux<WebSocketMessage> inboundFlux, List<String> sentFrames) {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);

        lenient().when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        lenient().when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost:8082" + path));
        lenient().when(session.getAttributes()).thenReturn(Collections.emptyMap());
        lenient().when(handshakeInfo.getAttributes()).thenReturn(Collections.emptyMap());
        lenient()
                .when(session.receive())
                .thenReturn(
                        inboundFlux.concatMap(
                                msg ->
                                        Mono.just(msg)
                                                .doFinally(
                                                        sig -> {
                                                            if (msg.getPayload()
                                                                    instanceof
                                                                    NettyDataBuffer nettyBuf) {
                                                                ReferenceCountUtil.release(
                                                                        nettyBuf.getNativeBuffer());
                                                            }
                                                        })));
        lenient()
                .when(session.textMessage(any()))
                .thenAnswer(
                        inv ->
                                new WebSocketMessage(
                                        WebSocketMessage.Type.TEXT,
                                        bufferFactory.wrap(
                                                ((String) inv.getArgument(0))
                                                        .getBytes(StandardCharsets.UTF_8))));
        lenient()
                .when(session.send(any()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            Flux<WebSocketMessage> outbound =
                                    (Flux<WebSocketMessage>) inv.getArgument(0);
                            return outbound.doOnNext(
                                            msg -> {
                                                sentFrames.add(msg.getPayloadAsText());
                                                msg.release();
                                            })
                                    .then();
                        });
        lenient().when(session.close(any())).thenReturn(Mono.empty());
        return session;
    }

    @Test
    @DisplayName("Rejects invalid paths (1002) and invalid operatorIds (1007)")
    void rejectsInvalidHandshakes() {
        WebSocketSession badPath = mockSession("/ws/unknown/path", Flux.empty(), new ArrayList<>());
        handler.handle(badPath).block();
        verify(badPath).close(argThat(status -> status.getCode() == 1002));

        WebSocketSession badOp =
                mockSession("/ws/operators/" + "a".repeat(65), Flux.empty(), new ArrayList<>());
        handler.handle(badOp).block();
        verify(badOp).close(argThat(status -> status.getCode() == 1007));
    }

    @Test
    @DisplayName(
            "Replays CONSOLE_SNAPSHOT with cached activeAssignment, handles HEARTBEAT, and releases"
                    + " Netty ByteBufs on binary/text frames")
    void replaysSnapshotHandlesHeartbeatAndReleasesBuffers() throws Exception {
        OperatorId opId = OperatorId.fromString("op-alex-1");
        OperatorPresence presence =
                new OperatorPresence(
                        opId,
                        "Alex Rivera",
                        OperatorAvailabilityStatus.BUSY,
                        Set.of(SkillGroup.DISRUPTION_DESK_GOLD),
                        Optional.of(CallId.generate()),
                        NOW);
        OperatorAssignmentPayloadDto cachedAssignment =
                Instancio.of(OperatorAssignmentPayloadDto.class)
                        .set(field(OperatorAssignmentPayloadDto::operatorId), "op-alex-1")
                        .set(field(OperatorAssignmentPayloadDto::pnr), "AB7DF9")
                        .set(field(OperatorAssignmentPayloadDto::transcript), List.of())
                        .set(field(OperatorAssignmentPayloadDto::metadata), Map.of())
                        .create();
        sessionRegistry.cacheActiveAssignment(opId, cachedAssignment);

        when(manageOperatorPresenceUseCase.getPresence(opId)).thenReturn(Optional.of(presence));
        when(manageOperatorPresenceUseCase.refreshHeartbeat(opId, NOW))
                .thenReturn(Optional.of(presence));
        when(inspectRoutingQueueUseCase.queueDepthsBySkillGroup())
                .thenReturn(Map.of(SkillGroup.DISRUPTION_DESK_GOLD, 1L));
        when(inspectRoutingQueueUseCase.listAllQueued()).thenReturn(List.of());

        ByteBuf[] textBuf = new ByteBuf[1];
        ByteBuf[] binaryBuf = new ByteBuf[1];
        WebSocketMessage heartbeatMsg = textMessage("{\"type\":\"HEARTBEAT\"}", textBuf);
        WebSocketMessage binaryMsg = binaryMessage(new byte[] {1, 2, 3, 4}, binaryBuf);

        List<String> sentFrames = Collections.synchronizedList(new ArrayList<>());
        WebSocketSession session =
                mockSession(
                        "/ws/operators/op-alex-1", Flux.just(heartbeatMsg, binaryMsg), sentFrames);

        handler.handle(session).block(Duration.ofSeconds(5));

        verify(manageOperatorPresenceUseCase).refreshHeartbeat(opId, NOW);
        assertThat(textBuf[0].refCnt()).isZero();
        assertThat(binaryBuf[0].refCnt()).isZero();
        assertThat(sessionRegistry.activeConnectionCount(opId)).isZero();

        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(sentFrames).hasSizeGreaterThanOrEqualTo(3));

        List<OperatorWsMessage> decoded = new ArrayList<>();
        for (String json : sentFrames) {
            decoded.add(objectMapper.readValue(json, OperatorWsMessage.class));
        }

        assertThat(decoded)
                .anySatisfy(
                        msg -> {
                            assertThat(msg)
                                    .isInstanceOf(OperatorWsMessage.ConsoleSnapshotMessage.class);
                            OperatorWsMessage.ConsoleSnapshotMessage snapshot =
                                    (OperatorWsMessage.ConsoleSnapshotMessage) msg;
                            assertThat(snapshot.activeAssignment()).isEqualTo(cachedAssignment);
                            assertThat(snapshot.presence().operatorId()).isEqualTo("op-alex-1");
                        })
                .anySatisfy(
                        msg -> assertThat(msg).isInstanceOf(OperatorWsMessage.PongMessage.class))
                .anySatisfy(
                        msg -> {
                            assertThat(msg)
                                    .isInstanceOf(OperatorWsMessage.ConsoleErrorMessage.class);
                            OperatorWsMessage.ConsoleErrorMessage err =
                                    (OperatorWsMessage.ConsoleErrorMessage) msg;
                            assertThat(err.code()).isEqualTo("UNSUPPORTED_BINARY_FRAME");
                        });
    }
}
