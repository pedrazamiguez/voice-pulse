package com.voicepulse.conversation.infrastructure.kafka.sequence;

import com.voicepulse.shared.model.CallId;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Thread-safe sequence verifier that validates monotonic continuity of audio frames per call
 * session.
 *
 * <p>Detects dropped frames (sequence gaps), duplicate frames, and out-of-order deliveries,
 * recording structured telemetry alerts and maintaining high-water mark sequence counters.
 */
@Slf4j
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
                sessionTrackers.computeIfAbsent(callId, id -> new SessionSequenceTracker());
        return tracker.verify(callId, sequenceNumber);
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

    /** Thread-safe sequence tracker dedicated to an individual call session. */
    private final class SessionSequenceTracker {

        private final AtomicLong lastSeq = new AtomicLong(-1L);
        private final AtomicLong totalFrames = new AtomicLong(0L);
        private final AtomicLong validFrames = new AtomicLong(0L);
        private final AtomicLong gapCount = new AtomicLong(0L);
        private final AtomicLong missingFrames = new AtomicLong(0L);
        private final AtomicLong duplicateCount = new AtomicLong(0L);
        private final AtomicLong outOfOrderCount = new AtomicLong(0L);

        synchronized SequenceValidationResult verify(CallId callId, long sequenceNumber) {
            totalFrames.incrementAndGet();
            long prevSeq = lastSeq.get();

            if (prevSeq < 0) {
                // First frame of the session
                lastSeq.set(sequenceNumber);
                if (sequenceNumber == 0L) {
                    validFrames.incrementAndGet();
                    globalValidFrames.incrementAndGet();
                    return new SequenceValidationResult.Valid(callId, sequenceNumber);
                }

                // Initial frame arrived with sequence > 0 (prior initial frames lost)
                long missing = sequenceNumber;
                gapCount.incrementAndGet();
                globalGapCount.incrementAndGet();
                missingFrames.addAndGet(missing);
                globalMissingFrames.addAndGet(missing);
                log.warn(
                        "Initial sequence gap detected: callId={}, expectedSeq=0, receivedSeq={},"
                                + " missingFrames={}",
                        callId,
                        sequenceNumber,
                        missing);
                return new SequenceValidationResult.GapDetected(
                        callId, sequenceNumber, 0L, missing);
            }

            if (sequenceNumber == prevSeq + 1) {
                lastSeq.set(sequenceNumber);
                validFrames.incrementAndGet();
                globalValidFrames.incrementAndGet();
                return new SequenceValidationResult.Valid(callId, sequenceNumber);
            }

            if (sequenceNumber == prevSeq) {
                duplicateCount.incrementAndGet();
                globalDuplicateCount.incrementAndGet();
                log.warn(
                        "Duplicate audio frame detected: callId={}, sequenceNumber={}",
                        callId,
                        sequenceNumber);
                return new SequenceValidationResult.Duplicate(callId, sequenceNumber);
            }

            if (sequenceNumber < prevSeq) {
                outOfOrderCount.incrementAndGet();
                globalOutOfOrderCount.incrementAndGet();
                log.warn(
                        "Out-of-order audio frame detected: callId={}, receivedSeq={}, lastSeq={}",
                        callId,
                        sequenceNumber,
                        prevSeq);
                return new SequenceValidationResult.OutOfOrder(callId, sequenceNumber, prevSeq);
            }

            // sequenceNumber > prevSeq + 1 (Gap / Dropped frames)
            long expected = prevSeq + 1;
            long missing = sequenceNumber - expected;
            lastSeq.set(sequenceNumber);
            gapCount.incrementAndGet();
            globalGapCount.incrementAndGet();
            missingFrames.addAndGet(missing);
            globalMissingFrames.addAndGet(missing);
            log.warn(
                    "Sequence gap detected: callId={}, expectedSeq={}, receivedSeq={},"
                            + " missingFrames={}",
                    callId,
                    expected,
                    sequenceNumber,
                    missing);
            return new SequenceValidationResult.GapDetected(
                    callId, sequenceNumber, expected, missing);
        }

        SequenceVerificationMetrics snapshot() {
            return new SequenceVerificationMetrics(
                    totalFrames.get(),
                    validFrames.get(),
                    gapCount.get(),
                    missingFrames.get(),
                    duplicateCount.get(),
                    outOfOrderCount.get(),
                    1);
        }
    }
}
