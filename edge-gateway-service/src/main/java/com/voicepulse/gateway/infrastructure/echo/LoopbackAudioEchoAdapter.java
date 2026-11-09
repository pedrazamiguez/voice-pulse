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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
 * registered in {@link ConcurrentSessionRegistry}, tracks microsecond round-trip time (RTT), and
 * enforces monotonic sequence continuity monitoring.
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
    private final ConcurrentHashMap<CallSessionId, SessionTracker> sessionTrackers =
            new ConcurrentHashMap<>();

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
        SessionTracker tracker = sessionTrackers.get(sessionId);
        if (tracker == null) {
            return EchoLatencyMetrics.empty();
        }
        return tracker.snapshot();
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

        SessionTracker tracker =
                sessionTrackers.computeIfAbsent(sessionId, id -> new SessionTracker());
        tracker.record(sequenceNumber, rttMicros);

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

    /** Thread-safe telemetry accumulator for per-session latency metrics. */
    private static final class SessionTracker {
        private final AtomicLong totalFrames = new AtomicLong(0L);
        private final AtomicLong totalMicros = new AtomicLong(0L);
        private final AtomicLong lastMicros = new AtomicLong(0L);
        private final AtomicLong minMicros = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxMicros = new AtomicLong(0L);
        private final AtomicLong lastSeq = new AtomicLong(-1L);
        private final AtomicLong discontinuities = new AtomicLong(0L);

        void record(long sequenceNumber, long rttMicros) {
            long prevSeq = lastSeq.getAndSet(sequenceNumber);
            if (prevSeq >= 0 && sequenceNumber != prevSeq + 1) {
                discontinuities.incrementAndGet();
                log.warn(
                        "Sequence discontinuity detected: expected seq={}, received seq={}",
                        prevSeq + 1,
                        sequenceNumber);
            }

            totalFrames.incrementAndGet();
            totalMicros.addAndGet(rttMicros);
            lastMicros.set(rttMicros);

            minMicros.accumulateAndGet(rttMicros, Math::min);
            maxMicros.accumulateAndGet(rttMicros, Math::max);
        }

        EchoLatencyMetrics snapshot() {
            long frames = totalFrames.get();
            if (frames == 0) {
                return EchoLatencyMetrics.empty();
            }
            long last = lastMicros.get();
            long min = minMicros.get();
            long max = maxMicros.get();
            double avgMs = (totalMicros.get() / (double) frames) / 1000.0;
            return new EchoLatencyMetrics(
                    last,
                    last / 1000.0,
                    (min == Long.MAX_VALUE) ? 0L : min,
                    max,
                    avgMs,
                    frames,
                    discontinuities.get());
        }
    }
}
