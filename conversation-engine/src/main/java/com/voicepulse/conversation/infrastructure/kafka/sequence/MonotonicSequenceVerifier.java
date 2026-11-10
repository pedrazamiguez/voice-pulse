package com.voicepulse.conversation.infrastructure.kafka.sequence;

import com.voicepulse.shared.model.CallId;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Thread-safe sequence verifier and session coordinator that tracks monotonic continuity of audio
 * frames across all active call sessions.
 *
 * <p>Coordinates per-session sequence trackers ({@link SessionSequenceTracker}), maintains
 * aggregated telemetry counters, and purges session state upon termination.
 */
@Component
public class MonotonicSequenceVerifier {

    private final ConcurrentHashMap<CallId, SessionSequenceTracker> sessionTrackers =
            new ConcurrentHashMap<>();

    private final AtomicLong globalTotalFrames = new AtomicLong(0L);
    private final AtomicLong globalValidFrames = new AtomicLong(0L);
    private final AtomicLong globalGapCount = new AtomicLong(0L);
    private final AtomicLong globalMissingFrames = new AtomicLong(0L);
    private final AtomicLong globalDuplicateCount = new AtomicLong(0L);
    private final AtomicLong globalOutOfOrderCount = new AtomicLong(0L);

    /**
     * Verifies the monotonic sequence number of an incoming audio frame for the given call ID.
     *
     * @param callId call session identifier
     * @param sequenceNumber sequence number of the incoming audio frame
     * @return outcome of the sequence verification
     */
    public SequenceValidationResult verify(CallId callId, long sequenceNumber) {
        Objects.requireNonNull(callId, "callId cannot be null");
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException(
                    "sequenceNumber cannot be negative: " + sequenceNumber);
        }

        globalTotalFrames.incrementAndGet();
        SessionSequenceTracker tracker =
                sessionTrackers.computeIfAbsent(callId, SessionSequenceTracker::new);
        SequenceValidationResult result = tracker.verify(sequenceNumber);
        updateGlobalMetrics(result);
        return result;
    }

    private void updateGlobalMetrics(SequenceValidationResult result) {
        switch (result) {
            case SequenceValidationResult.Valid v -> globalValidFrames.incrementAndGet();
            case SequenceValidationResult.GapDetected g -> {
                globalGapCount.incrementAndGet();
                globalMissingFrames.addAndGet(g.missingFramesCount());
            }
            case SequenceValidationResult.Duplicate d -> globalDuplicateCount.incrementAndGet();
            case SequenceValidationResult.OutOfOrder o -> globalOutOfOrderCount.incrementAndGet();
        }
    }

    /**
     * Retrieves cumulative telemetry metrics aggregated across all active and historical sessions.
     *
     * @return point-in-time metrics snapshot
     */
    public SequenceVerificationMetrics getMetrics() {
        return new SequenceVerificationMetrics(
                globalTotalFrames.get(),
                globalValidFrames.get(),
                globalGapCount.get(),
                globalMissingFrames.get(),
                globalDuplicateCount.get(),
                globalOutOfOrderCount.get(),
                sessionTrackers.size());
    }

    /**
     * Retrieves telemetry metrics for a specific call session.
     *
     * @param callId call session identifier
     * @return session metrics snapshot or {@link SequenceVerificationMetrics#empty()} if unobserved
     */
    public SequenceVerificationMetrics getMetrics(CallId callId) {
        Objects.requireNonNull(callId, "callId cannot be null");
        SessionSequenceTracker tracker = sessionTrackers.get(callId);
        if (tracker == null) {
            return SequenceVerificationMetrics.empty();
        }
        return tracker.snapshot();
    }

    /**
     * Purges sequence tracking state for a terminated call session to defend against memory leaks.
     *
     * @param callId call session identifier to evict
     */
    public void evict(CallId callId) {
        Objects.requireNonNull(callId, "callId cannot be null");
        sessionTrackers.remove(callId);
    }

    /** Clears all session tracking state and global metric counters. */
    public void clear() {
        sessionTrackers.clear();
        globalTotalFrames.set(0L);
        globalValidFrames.set(0L);
        globalGapCount.set(0L);
        globalMissingFrames.set(0L);
        globalDuplicateCount.set(0L);
        globalOutOfOrderCount.set(0L);
    }
}
