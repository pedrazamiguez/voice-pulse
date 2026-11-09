package com.voicepulse.conversation.infrastructure.kafka.sequence;

/**
 * Immutable point-in-time telemetry snapshot of audio sequence verification statistics.
 *
 * @param totalFramesProcessed total number of frames evaluated
 * @param validFrames count of strictly contiguous frames
 * @param gapCount count of sequence gap anomalies detected
 * @param totalMissingFrames cumulative count of dropped frames across all gaps
 * @param duplicateCount count of duplicate frames detected
 * @param outOfOrderCount count of late or out-of-order frames detected
 * @param activeSessionsCount count of active call sessions currently tracked
 */
public record SequenceVerificationMetrics(
        long totalFramesProcessed,
        long validFrames,
        long gapCount,
        long totalMissingFrames,
        long duplicateCount,
        long outOfOrderCount,
        int activeSessionsCount) {

    /**
     * Creates an empty metrics snapshot with all counters initialized to zero.
     *
     * @return empty metrics snapshot
     */
    public static SequenceVerificationMetrics empty() {
        return new SequenceVerificationMetrics(0L, 0L, 0L, 0L, 0L, 0L, 0);
    }
}
