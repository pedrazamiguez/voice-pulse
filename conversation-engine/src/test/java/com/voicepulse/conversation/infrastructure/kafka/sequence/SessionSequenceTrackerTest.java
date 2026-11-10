package com.voicepulse.conversation.infrastructure.kafka.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.shared.model.CallId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SessionSequenceTracker Unit Tests")
class SessionSequenceTrackerTest {

    private CallId callId;
    private SessionSequenceTracker tracker;

    @BeforeEach
    void setUp() {
        callId = CallId.generate();
        tracker = new SessionSequenceTracker(callId);
    }

    @Test
    @DisplayName("Constructor sets callId and initializes high-water mark to -1")
    void constructor_initializesCorrectly() {
        assertThat(tracker.callId()).isEqualTo(callId);
        assertThat(tracker.lastSequenceNumber()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Null callId throws NullPointerException")
    void constructor_nullCallId_throwsException() {
        assertThatThrownBy(() -> new SessionSequenceTracker(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId cannot be null");
    }

    @Test
    @DisplayName("Contiguous sequence returns Valid and advances sequence number")
    void verify_contiguousSequence_returnsValid() {
        SequenceValidationResult r0 = tracker.verify(0L);
        assertThat(r0).isInstanceOf(SequenceValidationResult.Valid.class);
        assertThat(r0.shouldDispatch()).isTrue();
        assertThat(r0.sequenceNumber()).isEqualTo(0L);
        assertThat(tracker.lastSequenceNumber()).isEqualTo(0L);

        SequenceValidationResult r1 = tracker.verify(1L);
        assertThat(r1).isInstanceOf(SequenceValidationResult.Valid.class);
        assertThat(r1.sequenceNumber()).isEqualTo(1L);
        assertThat(tracker.lastSequenceNumber()).isEqualTo(1L);

        SequenceValidationResult r2 = tracker.verify(2L);
        assertThat(r2).isInstanceOf(SequenceValidationResult.Valid.class);
        assertThat(r2.sequenceNumber()).isEqualTo(2L);

        SequenceVerificationMetrics metrics = tracker.snapshot();
        assertThat(metrics.totalFramesProcessed()).isEqualTo(3L);
        assertThat(metrics.validFrames()).isEqualTo(3L);
        assertThat(metrics.gapCount()).isZero();
        assertThat(metrics.totalMissingFrames()).isZero();
        assertThat(metrics.duplicateCount()).isZero();
        assertThat(metrics.outOfOrderCount()).isZero();
        assertThat(metrics.activeSessionsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sequence gap returns GapDetected with expected and missing frame counts")
    void verify_sequenceGap_returnsGapDetected() {
        tracker.verify(0L);
        tracker.verify(1L);

        // Gap: skip 2, 3, 4 -> receive 5
        SequenceValidationResult gapResult = tracker.verify(5L);
        assertThat(gapResult).isInstanceOf(SequenceValidationResult.GapDetected.class);
        assertThat(gapResult.shouldDispatch()).isTrue();

        SequenceValidationResult.GapDetected gap = (SequenceValidationResult.GapDetected) gapResult;
        assertThat(gap.expectedSequenceNumber()).isEqualTo(2L);
        assertThat(gap.sequenceNumber()).isEqualTo(5L);
        assertThat(gap.missingFramesCount()).isEqualTo(3L);
        assertThat(tracker.lastSequenceNumber()).isEqualTo(5L);

        // Next contiguous frame after gap: 6
        SequenceValidationResult r6 = tracker.verify(6L);
        assertThat(r6).isInstanceOf(SequenceValidationResult.Valid.class);
        assertThat(r6.sequenceNumber()).isEqualTo(6L);

        SequenceVerificationMetrics metrics = tracker.snapshot();
        assertThat(metrics.totalFramesProcessed()).isEqualTo(4L);
        assertThat(metrics.validFrames()).isEqualTo(3L);
        assertThat(metrics.gapCount()).isEqualTo(1L);
        assertThat(metrics.totalMissingFrames()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Initial non-zero frame returns GapDetected expecting frame 0")
    void verify_initialNonZeroFrame_returnsGapDetected() {
        SequenceValidationResult result = tracker.verify(3L);
        assertThat(result).isInstanceOf(SequenceValidationResult.GapDetected.class);
        assertThat(result.shouldDispatch()).isTrue();

        SequenceValidationResult.GapDetected gap = (SequenceValidationResult.GapDetected) result;
        assertThat(gap.expectedSequenceNumber()).isZero();
        assertThat(gap.sequenceNumber()).isEqualTo(3L);
        assertThat(gap.missingFramesCount()).isEqualTo(3L);
        assertThat(tracker.lastSequenceNumber()).isEqualTo(3L);

        SequenceVerificationMetrics metrics = tracker.snapshot();
        assertThat(metrics.totalFramesProcessed()).isEqualTo(1L);
        assertThat(metrics.gapCount()).isEqualTo(1L);
        assertThat(metrics.totalMissingFrames()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Duplicate frame returns Duplicate and rejects dispatch")
    void verify_duplicateFrame_returnsDuplicate() {
        tracker.verify(0L);
        tracker.verify(1L);

        SequenceValidationResult dupResult = tracker.verify(1L);
        assertThat(dupResult).isInstanceOf(SequenceValidationResult.Duplicate.class);
        assertThat(dupResult.shouldDispatch()).isFalse();
        assertThat(dupResult.sequenceNumber()).isEqualTo(1L);
        assertThat(tracker.lastSequenceNumber()).isEqualTo(1L);

        SequenceVerificationMetrics metrics = tracker.snapshot();
        assertThat(metrics.totalFramesProcessed()).isEqualTo(3L);
        assertThat(metrics.duplicateCount()).isEqualTo(1L);
        assertThat(metrics.validFrames()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Out-of-order frame returns OutOfOrder and preserves high-water mark")
    void verify_outOfOrderFrame_preservesHighWaterMark() {
        tracker.verify(0L);
        tracker.verify(1L);
        tracker.verify(5L); // advances high-water mark to 5

        // Late frame 3 arrives
        SequenceValidationResult oooResult = tracker.verify(3L);
        assertThat(oooResult).isInstanceOf(SequenceValidationResult.OutOfOrder.class);
        assertThat(oooResult.shouldDispatch()).isFalse();

        SequenceValidationResult.OutOfOrder ooo = (SequenceValidationResult.OutOfOrder) oooResult;
        assertThat(ooo.lastSequenceNumber()).isEqualTo(5L);
        assertThat(ooo.sequenceNumber()).isEqualTo(3L);
        assertThat(tracker.lastSequenceNumber()).isEqualTo(5L);

        // Frame 6 continues from high-water mark 5
        SequenceValidationResult r6 = tracker.verify(6L);
        assertThat(r6).isInstanceOf(SequenceValidationResult.Valid.class);
        assertThat(r6.sequenceNumber()).isEqualTo(6L);

        SequenceVerificationMetrics metrics = tracker.snapshot();
        assertThat(metrics.outOfOrderCount()).isEqualTo(1L);
        assertThat(metrics.validFrames()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Negative sequence number throws IllegalArgumentException")
    void verify_negativeSequence_throwsException() {
        assertThatThrownBy(() -> tracker.verify(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNumber cannot be negative");
    }
}
