package com.voicepulse.conversation.infrastructure.kafka.sequence;

import com.voicepulse.shared.model.CallId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * Thread-safe sequence tracker and state machine dedicated to an individual call session.
 *
 * <p>Validates monotonic sequence continuity, detects dropped or duplicate frames, maintains
 * session-level high-water marks, and logs alerts for sequence disruptions.
 */
@Slf4j
public class SessionSequenceTracker {

    private final CallId callId;
    private final AtomicLong lastSeq = new AtomicLong(-1L);
    private final AtomicLong totalFrames = new AtomicLong(0L);
    private final AtomicLong validFrames = new AtomicLong(0L);
    private final AtomicLong gapCount = new AtomicLong(0L);
    private final AtomicLong missingFrames = new AtomicLong(0L);
    private final AtomicLong duplicateCount = new AtomicLong(0L);
    private final AtomicLong outOfOrderCount = new AtomicLong(0L);

    /**
     * Constructs a sequence tracker for the given call session.
     *
     * @param callId call session identifier
     */
    public SessionSequenceTracker(CallId callId) {
        this.callId = Objects.requireNonNull(callId, "callId cannot be null");
    }

    /**
     * Verifies the incoming sequence number for this call session against the tracked high-water
     * mark.
     *
     * @param sequenceNumber sequence number of the incoming audio frame
     * @return sequence validation outcome
     */
    public synchronized SequenceValidationResult verify(long sequenceNumber) {
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException(
                    "sequenceNumber cannot be negative: " + sequenceNumber);
        }

        totalFrames.incrementAndGet();
        long prevSeq = lastSeq.get();

        if (prevSeq < 0) {
            // First frame of the session
            lastSeq.set(sequenceNumber);
            if (sequenceNumber == 0L) {
                validFrames.incrementAndGet();
                return new SequenceValidationResult.Valid(callId, sequenceNumber);
            }

            // Initial frame arrived with sequence > 0 (prior initial frames lost)
            long missing = sequenceNumber;
            gapCount.incrementAndGet();
            missingFrames.addAndGet(missing);
            log.warn(
                    "Initial sequence gap detected: callId={}, expectedSeq=0, receivedSeq={},"
                            + " missingFrames={}",
                    callId,
                    sequenceNumber,
                    missing);
            return new SequenceValidationResult.GapDetected(callId, sequenceNumber, 0L, missing);
        }

        if (sequenceNumber == prevSeq + 1) {
            lastSeq.set(sequenceNumber);
            validFrames.incrementAndGet();
            return new SequenceValidationResult.Valid(callId, sequenceNumber);
        }

        if (sequenceNumber == prevSeq) {
            duplicateCount.incrementAndGet();
            log.warn(
                    "Duplicate audio frame detected: callId={}, sequenceNumber={}",
                    callId,
                    sequenceNumber);
            return new SequenceValidationResult.Duplicate(callId, sequenceNumber);
        }

        if (sequenceNumber < prevSeq) {
            outOfOrderCount.incrementAndGet();
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
        missingFrames.addAndGet(missing);
        log.warn(
                "Sequence gap detected: callId={}, expectedSeq={}, receivedSeq={},"
                        + " missingFrames={}",
                callId,
                expected,
                sequenceNumber,
                missing);
        return new SequenceValidationResult.GapDetected(callId, sequenceNumber, expected, missing);
    }

    /**
     * Retrieves a point-in-time metrics snapshot for this session.
     *
     * @return session verification metrics
     */
    public SequenceVerificationMetrics snapshot() {
        return new SequenceVerificationMetrics(
                totalFrames.get(),
                validFrames.get(),
                gapCount.get(),
                missingFrames.get(),
                duplicateCount.get(),
                outOfOrderCount.get(),
                1);
    }

    /**
     * Returns the call identifier associated with this tracker.
     *
     * @return call ID
     */
    public CallId callId() {
        return callId;
    }

    /**
     * Returns the current high-water mark sequence number.
     *
     * @return highest observed contiguous or gap sequence number, or -1 if uninitialized
     */
    public long lastSequenceNumber() {
        return lastSeq.get();
    }
}
