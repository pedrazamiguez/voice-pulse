package com.voicepulse.gateway.infrastructure.kafka;

/**
 * Immutable telemetry record capturing cumulative published audio frames, delivery status, and
 * latency.
 */
public record KafkaProducerMetrics(
        long totalPublishedFrames,
        long successfulPublishedFrames,
        long failedPublishedFrames,
        long lastPublishLatencyMicros,
        double averagePublishLatencyMs) {

    /**
     * Creates an empty metrics snapshot with all counters and latencies initialized to zero.
     *
     * @return empty metrics record
     */
    public static KafkaProducerMetrics empty() {
        return new KafkaProducerMetrics(0L, 0L, 0L, 0L, 0.0);
    }
}
