package com.voicepulse.gateway.infrastructure.echo;

import com.voicepulse.shared.model.CallSessionId;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Thread-safe telemetry tracker accumulating real-time latency and frame sequencing metrics across
 * active loopback audio call sessions.
 */
@Slf4j
@Component
public class EchoLatencyTracker {

    private final ConcurrentHashMap<CallSessionId, SessionTelemetry> sessionTrackers =
            new ConcurrentHashMap<>();

    /**
     * Records a newly echoed audio frame for the given call session.
     *
     * @param sessionId the call session identifier
     * @param sequenceNumber sequence number of the echoed frame
     * @param rttMicros observed round-trip latency in microseconds
     */
    public void record(CallSessionId sessionId, long sequenceNumber, long rttMicros) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        sessionTrackers
                .computeIfAbsent(sessionId, id -> new SessionTelemetry())
                .record(sequenceNumber, rttMicros);
    }

    /**
     * Returns a point-in-time snapshot of latency metrics for a specific call session.
     *
     * @param sessionId the call session identifier
     * @return current latency metrics, or {@link EchoLatencyMetrics#empty()} if unobserved
     */
    public EchoLatencyMetrics getMetrics(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        SessionTelemetry tracker = sessionTrackers.get(sessionId);
        if (tracker == null) {
            return EchoLatencyMetrics.empty();
        }
        return tracker.snapshot();
    }

    /**
     * Purges recorded telemetry metrics for a terminated call session.
     *
     * @param sessionId the call session identifier
     */
    public void reset(CallSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        sessionTrackers.remove(sessionId);
    }

    /** Clears all session telemetry across all tracked call sessions. */
    public void clear() {
        sessionTrackers.clear();
    }

    /** Thread-safe telemetry accumulator for per-session latency metrics. */
    private static final class SessionTelemetry {
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
