package com.voicepulse.gateway.infrastructure.echo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.shared.model.CallSessionId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EchoLatencyTracker Telemetry Accumulator Tests")
class EchoLatencyTrackerTest {

    private EchoLatencyTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new EchoLatencyTracker();
    }

    @Test
    @DisplayName("Unobserved session returns empty metrics snapshot")
    void getMetrics_unobservedSession_returnsEmpty() {
        CallSessionId sessionId = CallSessionId.generate();

        EchoLatencyMetrics metrics = tracker.getMetrics(sessionId);

        assertThat(metrics).isEqualTo(EchoLatencyMetrics.empty());
        assertThat(metrics.totalFramesEchoed()).isZero();
        assertThat(metrics.lastRttMicros()).isZero();
        assertThat(metrics.minRttMicros()).isZero();
        assertThat(metrics.maxRttMicros()).isZero();
        assertThat(metrics.averageRttMs()).isZero();
        assertThat(metrics.sequenceDiscontinuities()).isZero();
    }

    @Test
    @DisplayName("Single frame records accurate RTT and cumulative frame count")
    void record_singleFrame_recordsAccurateRtt() {
        CallSessionId sessionId = CallSessionId.generate();

        tracker.record(sessionId, 0L, 2500L);

        EchoLatencyMetrics metrics = tracker.getMetrics(sessionId);
        assertThat(metrics.totalFramesEchoed()).isEqualTo(1L);
        assertThat(metrics.lastRttMicros()).isEqualTo(2500L);
        assertThat(metrics.lastRttMs()).isEqualTo(2.5);
        assertThat(metrics.minRttMicros()).isEqualTo(2500L);
        assertThat(metrics.maxRttMicros()).isEqualTo(2500L);
        assertThat(metrics.averageRttMs()).isEqualTo(2.5);
        assertThat(metrics.sequenceDiscontinuities()).isZero();
    }

    @Test
    @DisplayName("Multiple contiguous frames calculate min, max, and average RTT correctly")
    void record_multipleContiguousFrames_calculatesMinMaxAverage() {
        CallSessionId sessionId = CallSessionId.generate();

        tracker.record(sessionId, 0L, 1000L); // 1.0 ms
        tracker.record(sessionId, 1L, 3000L); // 3.0 ms
        tracker.record(sessionId, 2L, 2000L); // 2.0 ms

        EchoLatencyMetrics metrics = tracker.getMetrics(sessionId);
        assertThat(metrics.totalFramesEchoed()).isEqualTo(3L);
        assertThat(metrics.lastRttMicros()).isEqualTo(2000L);
        assertThat(metrics.lastRttMs()).isEqualTo(2.0);
        assertThat(metrics.minRttMicros()).isEqualTo(1000L);
        assertThat(metrics.maxRttMicros()).isEqualTo(3000L);
        assertThat(metrics.averageRttMs())
                .isEqualTo(2.0); // (1000 + 3000 + 2000) / 3 / 1000.0 = 2.0
        assertThat(metrics.sequenceDiscontinuities()).isZero();
    }

    @Test
    @DisplayName("Non-contiguous sequence numbers increment discontinuity counter")
    void record_sequenceGap_incrementsDiscontinuities() {
        CallSessionId sessionId = CallSessionId.generate();

        tracker.record(sessionId, 0L, 1500L);
        tracker.record(sessionId, 1L, 1500L);
        tracker.record(sessionId, 3L, 1500L); // Gap: expected 2L, got 3L
        tracker.record(sessionId, 4L, 1500L);
        tracker.record(sessionId, 10L, 1500L); // Gap: expected 5L, got 10L

        EchoLatencyMetrics metrics = tracker.getMetrics(sessionId);
        assertThat(metrics.totalFramesEchoed()).isEqualTo(5L);
        assertThat(metrics.sequenceDiscontinuities()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Multiple sessions maintain isolated telemetry state")
    void record_multipleSessions_isolatesTelemetry() {
        CallSessionId session1 = CallSessionId.generate();
        CallSessionId session2 = CallSessionId.generate();

        tracker.record(session1, 0L, 1000L);
        tracker.record(session1, 1L, 2000L);

        tracker.record(session2, 0L, 5000L);

        EchoLatencyMetrics metrics1 = tracker.getMetrics(session1);
        EchoLatencyMetrics metrics2 = tracker.getMetrics(session2);

        assertThat(metrics1.totalFramesEchoed()).isEqualTo(2L);
        assertThat(metrics1.maxRttMicros()).isEqualTo(2000L);

        assertThat(metrics2.totalFramesEchoed()).isEqualTo(1L);
        assertThat(metrics2.maxRttMicros()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("reset purges metrics for a specific session")
    void reset_purgesSessionMetrics() {
        CallSessionId sessionId = CallSessionId.generate();
        tracker.record(sessionId, 0L, 2000L);
        assertThat(tracker.getMetrics(sessionId).totalFramesEchoed()).isEqualTo(1L);

        tracker.reset(sessionId);

        assertThat(tracker.getMetrics(sessionId)).isEqualTo(EchoLatencyMetrics.empty());
    }

    @Test
    @DisplayName("clear purges metrics for all sessions")
    void clear_purgesAllSessionMetrics() {
        CallSessionId session1 = CallSessionId.generate();
        CallSessionId session2 = CallSessionId.generate();

        tracker.record(session1, 0L, 1000L);
        tracker.record(session2, 0L, 2000L);

        tracker.clear();

        assertThat(tracker.getMetrics(session1)).isEqualTo(EchoLatencyMetrics.empty());
        assertThat(tracker.getMetrics(session2)).isEqualTo(EchoLatencyMetrics.empty());
    }

    @Test
    @DisplayName("Null arguments throw NullPointerException")
    void nullArguments_throwNullPointerException() {
        assertThatThrownBy(() -> tracker.record(null, 0L, 1000L))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("sessionId cannot be null");

        assertThatThrownBy(() -> tracker.getMetrics(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("sessionId cannot be null");

        assertThatThrownBy(() -> tracker.reset(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("sessionId cannot be null");
    }

    @Test
    @DisplayName("Concurrent multi-threaded recording preserves exact frame count and boundaries")
    void concurrentRecording_preservesAccurateTotals() throws InterruptedException {
        CallSessionId sessionId = CallSessionId.generate();
        int threads = 10;
        int framesPerThread = 1000;
        long fixedRttMicros = 1500L;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int threadIndex = t;
            executor.submit(
                    () -> {
                        try {
                            startLatch.await();
                            for (int f = 0; f < framesPerThread; f++) {
                                long seq = (long) threadIndex * framesPerThread + f;
                                tracker.record(sessionId, seq, fixedRttMicros);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();

        EchoLatencyMetrics metrics = tracker.getMetrics(sessionId);
        assertThat(metrics.totalFramesEchoed()).isEqualTo((long) threads * framesPerThread);
        assertThat(metrics.minRttMicros()).isEqualTo(fixedRttMicros);
        assertThat(metrics.maxRttMicros()).isEqualTo(fixedRttMicros);
        assertThat(metrics.averageRttMs()).isEqualTo(fixedRttMicros / 1000.0);
    }
}
