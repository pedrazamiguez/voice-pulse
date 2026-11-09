package com.voicepulse.gateway.infrastructure.kafka;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Thread-safe, lock-free telemetry tracker monitoring Kafka audio frame publishing attempts,
 * delivery success, broker failures, and microsecond latencies.
 */
@Component
public class KafkaPublisherMetricsTracker {

    private final AtomicLong totalAttempts = new AtomicLong(0L);
    private final AtomicLong successfulFrames = new AtomicLong(0L);
    private final AtomicLong failedFrames = new AtomicLong(0L);
    private final AtomicLong totalLatencyMicros = new AtomicLong(0L);
    private final AtomicLong lastLatencyMicros = new AtomicLong(0L);

    /** Increments cumulative publishing attempt count. */
    public void recordAttempt() {
        totalAttempts.incrementAndGet();
    }

    /**
     * Records a successful delivery to Kafka with its observed round-trip latency.
     *
     * @param latencyMicros elapsed dispatch-to-broker round-trip latency in microseconds
     */
    public void recordSuccess(long latencyMicros) {
        long safeLatency = Math.max(0L, latencyMicros);
        successfulFrames.incrementAndGet();
        totalLatencyMicros.addAndGet(safeLatency);
        lastLatencyMicros.set(safeLatency);
    }

    /**
     * Records a failed dispatch or timeout with observed latency.
     *
     * @param latencyMicros elapsed dispatch-to-failure latency in microseconds
     */
    public void recordFailure(long latencyMicros) {
        long safeLatency = Math.max(0L, latencyMicros);
        failedFrames.incrementAndGet();
        totalLatencyMicros.addAndGet(safeLatency);
        lastLatencyMicros.set(safeLatency);
    }

    /**
     * Produces an immutable point-in-time snapshot of cumulative publishing metrics.
     *
     * @return metrics snapshot
     */
    public KafkaProducerMetrics snapshot() {
        long attempts = totalAttempts.get();
        long success = successfulFrames.get();
        long failed = failedFrames.get();
        long last = lastLatencyMicros.get();
        long completed = success + failed;
        double avgMs =
                (completed > 0L) ? ((totalLatencyMicros.get() / (double) completed) / 1000.0) : 0.0;

        return new KafkaProducerMetrics(attempts, success, failed, last, avgMs);
    }

    /** Resets all internal atomic counters to zero. */
    public void reset() {
        totalAttempts.set(0L);
        successfulFrames.set(0L);
        failedFrames.set(0L);
        totalLatencyMicros.set(0L);
        lastLatencyMicros.set(0L);
    }
}
