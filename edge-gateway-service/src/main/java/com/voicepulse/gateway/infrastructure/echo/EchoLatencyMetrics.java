package com.voicepulse.gateway.infrastructure.echo;

/**
 * Immutable metrics record exposing real-time audio echo latency telemetry.
 *
 * @param lastRttMicros round-trip latency of the most recent echoed audio frame in microseconds
 * @param lastRttMs round-trip latency of the most recent echoed audio frame in milliseconds
 * @param minRttMicros minimum observed round-trip latency in microseconds
 * @param maxRttMicros maximum observed round-trip latency in microseconds
 * @param averageRttMs average round-trip latency across all echoed frames in milliseconds
 * @param totalFramesEchoed cumulative count of frames echoed
 * @param sequenceDiscontinuities count of non-contiguous frame sequences detected
 */
public record EchoLatencyMetrics(
        long lastRttMicros,
        double lastRttMs,
        long minRttMicros,
        long maxRttMicros,
        double averageRttMs,
        long totalFramesEchoed,
        long sequenceDiscontinuities) {

    public static EchoLatencyMetrics empty() {
        return new EchoLatencyMetrics(0L, 0.0, 0L, 0L, 0.0, 0L, 0L);
    }
}
