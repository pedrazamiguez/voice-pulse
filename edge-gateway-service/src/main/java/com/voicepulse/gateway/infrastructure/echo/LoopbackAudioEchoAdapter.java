package com.voicepulse.gateway.infrastructure.echo;

import com.voicepulse.gateway.domain.ports.outbound.AudioFramePublisherPort;
import com.voicepulse.gateway.infrastructure.registry.ConcurrentSessionRegistry;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * Secondary outbound infrastructure adapter implementing {@link AudioFramePublisherPort}.
 *
 * <p>Reflects inbound PCM audio chunks back to the corresponding active {@link WebSocketSession}
 * registered in {@link ConcurrentSessionRegistry}, delegates microsecond round-trip time (RTT)
 * tracking and sequence continuity monitoring to {@link EchoLatencyTracker}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "voicepulse.gateway",
        name = "audio-publisher",
        havingValue = "loopback")
public class LoopbackAudioEchoAdapter implements AudioFramePublisherPort {

    private final @NonNull ConcurrentSessionRegistry sessionRegistry;
    private final @NonNull EchoLatencyTracker latencyTracker;

    @Override
    public void publishAudioChunk(AudioChunk chunk) {
        Objects.requireNonNull(chunk, "chunk cannot be null");
        echoAudioFrame(
                chunk.sessionId(), chunk.sequenceNumber(), chunk.timestamp(), chunk.pcmData());
    }

    @Override
    public void publishAudioFrame(CallId callId, long sequenceNumber, byte[] audioPayload) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(audioPayload, "audioPayload cannot be null");
        CallSessionId sessionId = CallSessionId.fromCallId(callId);
        echoAudioFrame(sessionId, sequenceNumber, Instant.now(), audioPayload);
    }

    /**
     * Retrieves current latency and throughput metrics for a specific call session.
     *
     * @param sessionId call identifier
     * @return current latency metrics
     */
    public EchoLatencyMetrics getMetrics(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        return latencyTracker.getMetrics(sessionId);
    }

    private void echoAudioFrame(
            CallSessionId sessionId,
            long sequenceNumber,
            Instant frameIngressTime,
            byte[] payload) {
        Optional<WebSocketSession> sessionOpt = sessionRegistry.getSession(sessionId);
        if (sessionOpt.isEmpty() || !sessionOpt.get().isOpen()) {
            log.debug(
                    "Dropping echo frame: active WebSocket session not found or closed for"
                            + " callId: {}",
                    sessionId);
            return;
        }

        WebSocketSession session = sessionOpt.get();
        Instant now = Instant.now();
        long rttMicros = Math.max(0L, ChronoUnit.MICROS.between(frameIngressTime, now));
        double rttMs = rttMicros / 1000.0;

        latencyTracker.record(sessionId, sequenceNumber, rttMicros);

        WebSocketMessage echoMessage = session.binaryMessage(factory -> factory.wrap(payload));
        boolean emitted = sessionRegistry.emitMessage(sessionId, echoMessage);

        if (emitted) {
            log.info(
                    "Audio frame echoed: callId={}, seq={}, bytes={}, rttMicros={}, rttMs={}",
                    sessionId,
                    sequenceNumber,
                    payload.length,
                    rttMicros,
                    rttMs);
        } else {
            log.warn(
                    "Backpressure drop: failed to emit echo frame for callId={}, seq={}",
                    sessionId,
                    sequenceNumber);
        }
    }
}
