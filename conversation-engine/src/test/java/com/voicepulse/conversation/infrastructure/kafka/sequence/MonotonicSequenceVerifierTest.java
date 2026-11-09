package com.voicepulse.conversation.infrastructure.kafka.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.shared.model.CallId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MonotonicSequenceVerifier Unit Tests")
class MonotonicSequenceVerifierTest {

    private MonotonicSequenceVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new MonotonicSequenceVerifier();
    }

    @Test
    @DisplayName("Contiguous sequences return Valid and increment valid metrics")
    void verify_contiguousSequence_returnsValid() {
        CallId callId = CallId.generate();

        SequenceValidationResult r0 = verifier.verify(callId, 0L);
        assertThat(r0).isInstanceOf(SequenceValidationResult.Valid.class);
        assertThat(r0.shouldDispatch()).isTrue();
        assertThat(r0.sequenceNumber()).isEqualTo(0L);
        assertThat(r0.callId()).isEqualTo(callId);

        SequenceValidationResult r1 = verifier.verify(callId, 1L);
        assertThat(r1).isInstanceOf(SequenceValidationResult.Valid.class);
        assertThat(r1.shouldDispatch()).isTrue();
        assertThat(r1.sequenceNumber()).isEqualTo(1L);

        SequenceValidationResult r2 = verifier.verify(callId, 2L);
        assertThat(r2).isInstanceOf(SequenceValidationResult.Valid.class);

        SequenceVerificationMetrics sessionMetrics = verifier.getMetrics(callId);
        assertThat(sessionMetrics.totalFramesProcessed()).isEqualTo(3L);
        assertThat(sessionMetrics.validFrames()).isEqualTo(3L);
        assertThat(sessionMetrics.gapCount()).isZero();
        assertThat(sessionMetrics.totalMissingFrames()).isZero();
        assertThat(sessionMetrics.duplicateCount()).isZero();
        assertThat(sessionMetrics.outOfOrderCount()).isZero();

        SequenceVerificationMetrics globalMetrics = verifier.getMetrics();
        assertThat(globalMetrics.totalFramesProcessed()).isEqualTo(3L);
        assertThat(globalMetrics.validFrames()).isEqualTo(3L);
        assertThat(globalMetrics.activeSessionsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Sequence gap returns GapDetected, tracks missing frames, and subsequent frame resumes"
                    + " validly")
    void verify_sequenceGap_returnsGapDetectedAndResumesContiguously() {
        CallId callId = CallId.generate();

        verifier.verify(callId, 0L);
        verifier.verify(callId, 1L);

        // Gap: skip 2, 3, 4 -> receive 5
        SequenceValidationResult gapResult = verifier.verify(callId, 5L);
        assertThat(gapResult).isInstanceOf(SequenceValidationResult.GapDetected.class);
        assertThat(gapResult.shouldDispatch()).isTrue();

        SequenceValidationResult.GapDetected gap = (SequenceValidationResult.GapDetected) gapResult;
        assertThat(gap.expectedSequenceNumber()).isEqualTo(2L);
        assertThat(gap.sequenceNumber()).isEqualTo(5L);
        assertThat(gap.missingFramesCount()).isEqualTo(3L);

        // Next contiguous frame after gap: 6
        SequenceValidationResult r6 = verifier.verify(callId, 6L);
        assertThat(r6).isInstanceOf(SequenceValidationResult.Valid.class);
        assertThat(r6.sequenceNumber()).isEqualTo(6L);

        SequenceVerificationMetrics metrics = verifier.getMetrics(callId);
        assertThat(metrics.totalFramesProcessed()).isEqualTo(4L);
        assertThat(metrics.validFrames()).isEqualTo(3L);
        assertThat(metrics.gapCount()).isEqualTo(1L);
        assertThat(metrics.totalMissingFrames()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Initial non-zero frame returns GapDetected with expected 0")
    void verify_initialNonZeroFrame_returnsGapDetected() {
        CallId callId = CallId.generate();

        SequenceValidationResult result = verifier.verify(callId, 4L);
        assertThat(result).isInstanceOf(SequenceValidationResult.GapDetected.class);
        assertThat(result.shouldDispatch()).isTrue();

        SequenceValidationResult.GapDetected gap = (SequenceValidationResult.GapDetected) result;
        assertThat(gap.expectedSequenceNumber()).isZero();
        assertThat(gap.sequenceNumber()).isEqualTo(4L);
        assertThat(gap.missingFramesCount()).isEqualTo(4L);

        SequenceVerificationMetrics metrics = verifier.getMetrics(callId);
        assertThat(metrics.gapCount()).isEqualTo(1L);
        assertThat(metrics.totalMissingFrames()).isEqualTo(4L);
    }

    @Test
    @DisplayName(
            "Duplicate frame returns Duplicate, increments duplicate metric, and rejects dispatch")
    void verify_duplicateFrame_returnsDuplicateAndRejectsDispatch() {
        CallId callId = CallId.generate();

        verifier.verify(callId, 0L);
        verifier.verify(callId, 1L);

        // Duplicate of frame 1
        SequenceValidationResult dupResult = verifier.verify(callId, 1L);
        assertThat(dupResult).isInstanceOf(SequenceValidationResult.Duplicate.class);
        assertThat(dupResult.shouldDispatch()).isFalse();
        assertThat(dupResult.sequenceNumber()).isEqualTo(1L);

        SequenceVerificationMetrics metrics = verifier.getMetrics(callId);
        assertThat(metrics.duplicateCount()).isEqualTo(1L);
        assertThat(metrics.validFrames()).isEqualTo(2L);
        assertThat(metrics.totalFramesProcessed()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Out-of-order late frame returns OutOfOrder and does not regress high-water mark")
    void verify_outOfOrderFrame_returnsOutOfOrderAndPreservesHighWaterMark() {
        CallId callId = CallId.generate();

        verifier.verify(callId, 0L);
        verifier.verify(callId, 1L);
        verifier.verify(callId, 5L); // advances high-water mark to 5

        // Late frame 3 arrives
        SequenceValidationResult outOfOrderResult = verifier.verify(callId, 3L);
        assertThat(outOfOrderResult).isInstanceOf(SequenceValidationResult.OutOfOrder.class);
        assertThat(outOfOrderResult.shouldDispatch()).isFalse();

        SequenceValidationResult.OutOfOrder ooo =
                (SequenceValidationResult.OutOfOrder) outOfOrderResult;
        assertThat(ooo.lastSequenceNumber()).isEqualTo(5L);
        assertThat(ooo.sequenceNumber()).isEqualTo(3L);

        // Next frame 6 should continue from high-water mark 5
        SequenceValidationResult r6 = verifier.verify(callId, 6L);
        assertThat(r6).isInstanceOf(SequenceValidationResult.Valid.class);

        SequenceVerificationMetrics metrics = verifier.getMetrics(callId);
        assertThat(metrics.outOfOrderCount()).isEqualTo(1L);
        assertThat(metrics.validFrames()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Multiple call sessions maintain isolated sequence tracking")
    void verify_multipleSessions_maintainIsolatedTracking() {
        CallId callA = CallId.generate();
        CallId callB = CallId.generate();

        verifier.verify(callA, 0L);
        verifier.verify(callB, 0L);
        verifier.verify(callA, 1L);
        verifier.verify(callB, 1L);
        verifier.verify(callB, 2L);

        assertThat(verifier.getMetrics(callA).totalFramesProcessed()).isEqualTo(2L);
        assertThat(verifier.getMetrics(callB).totalFramesProcessed()).isEqualTo(3L);
        assertThat(verifier.getMetrics().activeSessionsCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Evict removes session tracking state to defend against memory leaks")
    void evict_purgesSessionTracking() {
        CallId callId = CallId.generate();
        verifier.verify(callId, 0L);
        verifier.verify(callId, 1L);

        assertThat(verifier.getMetrics().activeSessionsCount()).isEqualTo(1);
        verifier.evict(callId);
        assertThat(verifier.getMetrics().activeSessionsCount()).isZero();
        assertThat(verifier.getMetrics(callId)).isEqualTo(SequenceVerificationMetrics.empty());
    }

    @Test
    @DisplayName("Clear resets all session and global metrics")
    void clear_resetsAllMetrics() {
        CallId callId = CallId.generate();
        verifier.verify(callId, 0L);
        verifier.verify(callId, 2L);

        verifier.clear();
        SequenceVerificationMetrics metrics = verifier.getMetrics();
        assertThat(metrics.totalFramesProcessed()).isZero();
        assertThat(metrics.validFrames()).isZero();
        assertThat(metrics.gapCount()).isZero();
        assertThat(metrics.totalMissingFrames()).isZero();
        assertThat(metrics.activeSessionsCount()).isZero();
    }

    @Test
    @DisplayName("Negative sequence number throws IllegalArgumentException")
    void verify_negativeSequence_throwsException() {
        CallId callId = CallId.generate();
        assertThatThrownBy(() -> verifier.verify(callId, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNumber cannot be negative");
    }

    @Test
    @DisplayName("Null callId throws NullPointerException")
    void verify_nullCallId_throwsException() {
        assertThatThrownBy(() -> verifier.verify(null, 0L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId cannot be null");
    }
}
