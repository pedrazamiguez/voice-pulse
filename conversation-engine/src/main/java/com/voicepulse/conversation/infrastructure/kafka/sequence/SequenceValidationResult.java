package com.voicepulse.conversation.infrastructure.kafka.sequence;

import com.voicepulse.shared.model.CallId;

/**
 * Sealed hierarchy representing the outcome of verifying an incoming audio frame's sequence number
 * against the tracked session sequence.
 */
public sealed interface SequenceValidationResult
        permits SequenceValidationResult.Valid,
                SequenceValidationResult.GapDetected,
                SequenceValidationResult.Duplicate,
                SequenceValidationResult.OutOfOrder {

    /**
     * Session identifier for this validation.
     *
     * @return call ID
     */
    CallId callId();

    /**
     * Observed sequence number on the frame.
     *
     * @return sequence number
     */
    long sequenceNumber();

    /**
     * Indicates whether this frame should be dispatched downstream to the worker pool and domain
     * use case.
     *
     * @return true for contiguous frames and recoverable gaps; false for duplicates and late frames
     */
    default boolean shouldDispatch() {
        return this instanceof Valid || this instanceof GapDetected;
    }

    /**
     * Contiguous sequential frame matching expectation.
     *
     * @param callId call ID
     * @param sequenceNumber sequence number
     */
    record Valid(CallId callId, long sequenceNumber) implements SequenceValidationResult {}

    /**
     * Sequence gap detected where one or more intermediate frames were dropped.
     *
     * @param callId call ID
     * @param sequenceNumber sequence number
     * @param expectedSequenceNumber expected contiguous sequence number
     * @param missingFramesCount count of dropped frames
     */
    record GapDetected(
            CallId callId,
            long sequenceNumber,
            long expectedSequenceNumber,
            long missingFramesCount)
            implements SequenceValidationResult {}

    /**
     * Duplicate frame with identical sequence number to the previously processed frame.
     *
     * @param callId call ID
     * @param sequenceNumber sequence number
     */
    record Duplicate(CallId callId, long sequenceNumber) implements SequenceValidationResult {}

    /**
     * Out-of-order or late arriving frame with sequence lower than the session's high-water mark.
     *
     * @param callId call ID
     * @param sequenceNumber sequence number
     * @param lastSequenceNumber highest sequence number previously observed
     */
    record OutOfOrder(CallId callId, long sequenceNumber, long lastSequenceNumber)
            implements SequenceValidationResult {}
}
