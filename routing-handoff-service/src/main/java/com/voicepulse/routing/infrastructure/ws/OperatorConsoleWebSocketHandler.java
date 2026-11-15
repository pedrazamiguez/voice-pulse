package com.voicepulse.routing.infrastructure.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.domain.model.OperatorPresence;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.domain.ports.inbound.InspectRoutingQueueUseCase;
import com.voicepulse.routing.domain.ports.inbound.ManageOperatorPresenceUseCase;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorAssignmentPayloadDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorPresenceDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorWsClientCommand;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorWsMessage;
import com.voicepulse.routing.infrastructure.ws.dto.QueueOverviewDto;
import com.voicepulse.routing.infrastructure.ws.mapper.OperatorConsoleMapper;
import com.voicepulse.routing.infrastructure.ws.mapper.TranscriptContextExtractor;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Primary inbound reactive WebSocket adapter serving {@code /ws/operators/{operatorId}} for the
 * Operator Web Console.
 *
 * <p>Replays an anti-race initial {@code CONSOLE_SNAPSHOT}, streams live ticket assignments, queue
 * metrics, and handoff state changes, handles {@code HEARTBEAT}/{@code PING}/{@code
 * REQUEST_SNAPSHOT} JSON commands off the Netty event loop via {@link VirtualThreadTaskOffloader},
 * and deterministically releases all inbound buffers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperatorConsoleWebSocketHandler implements WebSocketHandler {

    private static final String CMD_HEARTBEAT = "HEARTBEAT";
    private static final String CMD_PING = "PING";
    private static final String CMD_REQUEST_SNAPSHOT = "REQUEST_SNAPSHOT";

    private final @NonNull OperatorConsoleUriParser uriParser;
    private final @NonNull OperatorConsoleSessionRegistry sessionRegistry;
    private final @NonNull ManageOperatorPresenceUseCase manageOperatorPresenceUseCase;
    private final @NonNull InspectRoutingQueueUseCase inspectRoutingQueueUseCase;
    private final @NonNull OperatorConsoleMapper mapper;
    private final @NonNull TranscriptContextExtractor transcriptContextExtractor;
    private final @NonNull VirtualThreadTaskOffloader taskOffloader;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull Clock clock;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        OperatorConsoleUriParser.ParseResult parseResult = uriParser.parse(session);
        return switch (parseResult) {
            case OperatorConsoleUriParser.ParseResult.InvalidPath invalidPath -> {
                log.warn("Operator WS handshake rejected: invalid path={}", invalidPath.path());
                yield session.close(
                        new CloseStatus(1002, "Invalid path: expected /ws/operators/{operatorId}"));
            }
            case OperatorConsoleUriParser.ParseResult.InvalidOperatorId invalidOperatorId -> {
                log.warn(
                        "Operator WS handshake rejected: invalid operatorId={}, reason={}",
                        invalidOperatorId.rawOperatorId(),
                        invalidOperatorId.reason());
                yield session.close(
                        new CloseStatus(1007, "Invalid operatorId: " + invalidOperatorId.reason()));
            }
            case OperatorConsoleUriParser.ParseResult.Success success ->
                    handleOperatorSession(session, success.operatorId());
        };
    }

    private Mono<Void> handleOperatorSession(WebSocketSession session, OperatorId operatorId) {
        String connectionId = UUID.randomUUID().toString();
        Flux<OperatorWsMessage> outboundMessages =
                sessionRegistry.register(operatorId, connectionId);

        Mono<OperatorWsMessage> initialSnapshot =
                taskOffloader
                        .supply(() -> buildSnapshotMessage(operatorId))
                        .onErrorResume(
                                ex -> {
                                    log.warn(
                                            "Failed to build initial snapshot for operatorId={}",
                                            operatorId,
                                            ex);
                                    return Mono.just(
                                            new OperatorWsMessage.ConsoleErrorMessage(
                                                    "SNAPSHOT_ERROR",
                                                    "Failed to build initial console snapshot",
                                                    clock.instant()));
                                });

        Flux<WebSocketMessage> outboundFrames =
                Flux.merge(outboundMessages, initialSnapshot)
                        .map(message -> encodeOutboundMessage(session, message));

        Mono<Void> inbound =
                session.receive()
                        .concatMap(
                                message -> processInboundMessage(operatorId, connectionId, message))
                        .doOnError(
                                err ->
                                        log.error(
                                                "Inbound WS pipeline error for operatorId={}",
                                                operatorId,
                                                err))
                        .then()
                        .doFinally(
                                signalType -> {
                                    sessionRegistry.unregister(operatorId, connectionId);
                                    log.debug(
                                            "Operator WS session closed: operatorId={},"
                                                    + " connectionId={}, signal={}",
                                            operatorId,
                                            connectionId,
                                            signalType);
                                });

        Mono<Void> outbound =
                session.send(outboundFrames)
                        .doOnError(
                                err ->
                                        log.error(
                                                "Outbound WS pipeline error for operatorId={}",
                                                operatorId,
                                                err));

        return Mono.when(inbound, outbound)
                .doFinally(signalType -> sessionRegistry.unregister(operatorId, connectionId));
    }

    private Mono<Void> processInboundMessage(
            OperatorId operatorId, String connectionId, WebSocketMessage message) {
        if (message.getType() == WebSocketMessage.Type.BINARY) {
            sessionRegistry.sendToConnection(
                    operatorId,
                    connectionId,
                    new OperatorWsMessage.ConsoleErrorMessage(
                            "UNSUPPORTED_BINARY_FRAME",
                            "Operator signaling WebSocket accepts JSON text frames only",
                            clock.instant()));
            return Mono.empty();
        }

        if (message.getType() != WebSocketMessage.Type.TEXT) {
            return Mono.empty();
        }

        String rawText;
        try {
            rawText = readTextSafely(message);
        } catch (RuntimeException ex) {
            sessionRegistry.sendToConnection(
                    operatorId,
                    connectionId,
                    new OperatorWsMessage.ConsoleErrorMessage(
                            "INVALID_FRAME", "Failed to read text frame payload", clock.instant()));
            return Mono.empty();
        }

        OperatorWsClientCommand command;
        try {
            command = objectMapper.readValue(rawText, OperatorWsClientCommand.class);
        } catch (JsonProcessingException ex) {
            sessionRegistry.sendToConnection(
                    operatorId,
                    connectionId,
                    new OperatorWsMessage.ConsoleErrorMessage(
                            "INVALID_JSON", "Malformed JSON command envelope", clock.instant()));
            return Mono.empty();
        }

        String commandType = command == null ? "" : command.normalizedType();
        return switch (commandType) {
            case CMD_HEARTBEAT -> handleHeartbeatCommand(operatorId, connectionId);
            case CMD_PING -> {
                sessionRegistry.sendToConnection(
                        operatorId,
                        connectionId,
                        new OperatorWsMessage.PongMessage(operatorId.value(), clock.instant()));
                yield Mono.empty();
            }
            case CMD_REQUEST_SNAPSHOT -> handleSnapshotCommand(operatorId, connectionId);
            default -> {
                sessionRegistry.sendToConnection(
                        operatorId,
                        connectionId,
                        new OperatorWsMessage.ConsoleErrorMessage(
                                "UNKNOWN_COMMAND",
                                "Unsupported command type: " + commandType,
                                clock.instant()));
                yield Mono.empty();
            }
        };
    }

    private Mono<Void> handleHeartbeatCommand(OperatorId operatorId, String connectionId) {
        return taskOffloader
                .run(
                        () -> {
                            Instant now = clock.instant();
                            manageOperatorPresenceUseCase.refreshHeartbeat(operatorId, now);
                        })
                .then(
                        Mono.fromRunnable(
                                () ->
                                        sessionRegistry.sendToConnection(
                                                operatorId,
                                                connectionId,
                                                new OperatorWsMessage.PongMessage(
                                                        operatorId.value(), clock.instant()))))
                .onErrorResume(
                        ex -> {
                            log.warn("Heartbeat refresh failed for operatorId={}", operatorId, ex);
                            sessionRegistry.sendToConnection(
                                    operatorId,
                                    connectionId,
                                    new OperatorWsMessage.ConsoleErrorMessage(
                                            "HEARTBEAT_FAILED",
                                            "Failed to refresh operator heartbeat",
                                            clock.instant()));
                            return Mono.empty();
                        })
                .then();
    }

    private Mono<Void> handleSnapshotCommand(OperatorId operatorId, String connectionId) {
        return taskOffloader
                .supply(() -> buildSnapshotMessage(operatorId))
                .doOnNext(
                        snapshot ->
                                sessionRegistry.sendToConnection(
                                        operatorId, connectionId, snapshot))
                .onErrorResume(
                        ex -> {
                            log.warn("Snapshot refresh failed for operatorId={}", operatorId, ex);
                            sessionRegistry.sendToConnection(
                                    operatorId,
                                    connectionId,
                                    new OperatorWsMessage.ConsoleErrorMessage(
                                            "SNAPSHOT_ERROR",
                                            "Failed to refresh console snapshot",
                                            clock.instant()));
                            return Mono.empty();
                        })
                .then();
    }

    private OperatorWsMessage buildSnapshotMessage(OperatorId operatorId) {
        Instant now = clock.instant();
        Optional<OperatorPresence> presenceOpt =
                manageOperatorPresenceUseCase.getPresence(operatorId);
        OperatorPresenceDto presenceDto = presenceOpt.map(mapper::toPresenceDto).orElse(null);

        OperatorAssignmentPayloadDto activeAssignment =
                sessionRegistry.getActiveAssignment(operatorId).orElse(null);
        if (activeAssignment == null) {
            activeAssignment =
                    presenceOpt
                            .flatMap(OperatorPresence::activeCallId)
                            .flatMap(inspectRoutingQueueUseCase::findTicketByCallId)
                            .map(
                                    ticket ->
                                            mapper.toTicketPayload(
                                                    ticket, transcriptContextExtractor))
                            .orElse(null);
        }

        Map<SkillGroup, Long> depths = inspectRoutingQueueUseCase.queueDepthsBySkillGroup();
        List<QueuedTicketItem> queuedItems = inspectRoutingQueueUseCase.listAllQueued();
        QueueOverviewDto queueOverview =
                mapper.toQueueOverviewDto(
                        "ALL", depths, queuedItems, now, transcriptContextExtractor);

        return new OperatorWsMessage.ConsoleSnapshotMessage(
                presenceDto, activeAssignment, queueOverview, now);
    }

    private WebSocketMessage encodeOutboundMessage(
            WebSocketSession session, OperatorWsMessage message) {
        try {
            return session.textMessage(objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Failed to serialize OperatorWsMessage: " + message.getClass().getSimpleName(),
                    ex);
        }
    }

    private static String readTextSafely(WebSocketMessage message) {
        DataBuffer payload = message.getPayload();
        DataBufferUtils.retain(payload);
        try {
            byte[] bytes = new byte[payload.readableByteCount()];
            payload.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            releaseBuffer(payload);
        }
    }

    private static void releaseBuffer(DataBuffer dataBuffer) {
        if (dataBuffer == null) {
            return;
        }
        if (dataBuffer instanceof NettyDataBuffer nettyDataBuffer) {
            ReferenceCountUtil.release(nettyDataBuffer.getNativeBuffer());
        } else {
            DataBufferUtils.release(dataBuffer);
        }
    }
}
