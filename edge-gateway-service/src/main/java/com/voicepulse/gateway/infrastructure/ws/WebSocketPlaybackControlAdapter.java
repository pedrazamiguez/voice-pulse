package com.voicepulse.gateway.infrastructure.ws;

import com.voicepulse.gateway.domain.ports.outbound.AudioPlaybackControlPort;
import com.voicepulse.gateway.infrastructure.registry.ConcurrentSessionRegistry;
import com.voicepulse.shared.model.CallSessionId;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * Secondary outbound infrastructure adapter implementing {@link AudioPlaybackControlPort}.
 *
 * <p>Purges queued outbound audio chunks from {@link ConcurrentSessionRegistry} to halt audio
 * playback with sub-50ms latency, and dispatches a JSON control signal ({@code OP_INTERRUPT}) over
 * WebSocket to instruct the caller client to clear its local playback buffer immediately.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketPlaybackControlAdapter implements AudioPlaybackControlPort {

    private final @NonNull ConcurrentSessionRegistry sessionRegistry;

    @Override
    public void interruptPlayback(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");

        int purgedCount = sessionRegistry.purgeOutboundBuffer(sessionId);

        Optional<WebSocketSession> sessionOpt = sessionRegistry.getSession(sessionId);
        if (sessionOpt.isEmpty() || !sessionOpt.get().isOpen()) {
            log.debug(
                    "Barge-in interrupt: active WebSocket session not found or closed for"
                            + " callId: {}, purged {} frames",
                    sessionId,
                    purgedCount);
            return;
        }

        WebSocketSession session = sessionOpt.get();
        String payload = "{\"op\":\"INTERRUPT\",\"callId\":\"" + sessionId.value() + "\"}";
        WebSocketMessage interruptMessage = session.textMessage(payload);

        boolean emitted = sessionRegistry.emitMessage(sessionId, interruptMessage);
        log.info(
                "Barge-in playback interrupted: callId={}, purgedFrames={}, emittedInterrupt={}",
                sessionId,
                purgedCount,
                emitted);
    }
}
