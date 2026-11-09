package com.voicepulse.gateway.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KafkaPublisherMetricsTrackerTest {

    private KafkaPublisherMetricsTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new KafkaPublisherMetricsTracker();
    }

    @Test
    @DisplayName("Empty tracker produces snapshot with zeroed counters")
    void emptyTracker_producesZeroedSnapshot() {
        KafkaProducerMetrics snapshot = tracker.snapshot();

        assertThat(snapshot).isEqualTo(KafkaProducerMetrics.empty());
        assertThat(snapshot.totalPublishedFrames()).isZero();
        assertThat(snapshot.successfulPublishedFrames()).isZero();
        assertThat(snapshot.failedPublishedFrames()).isZero();
        assertThat(snapshot.lastPublishLatencyMicros()).isZero();
        assertThat(snapshot.averagePublishLatencyMs()).isZero();
    }

    @Test
    @DisplayName("Recording attempt and success updates counters and latency")
    void recordAttemptAndSuccess_updatesCountersAndLatency() {
        tracker.recordAttempt();
        tracker.recordSuccess(5000L);

        KafkaProducerMetrics snapshot = tracker.snapshot();

        assertThat(snapshot.totalPublishedFrames()).isEqualTo(1L);
        assertThat(snapshot.successfulPublishedFrames()).isEqualTo(1L);
        assertThat(snapshot.failedPublishedFrames()).isZero();
        assertThat(snapshot.lastPublishLatencyMicros()).isEqualTo(5000L);
        assertThat(snapshot.averagePublishLatencyMs()).isCloseTo(5.0, within(0.001));
    }

    @Test
    @DisplayName("Recording failure updates failure counter and average latency")
    void recordFailure_updatesFailureCounterAndLatency() {
        tracker.recordAttempt();
        tracker.recordFailure(3000L);

        KafkaProducerMetrics snapshot = tracker.snapshot();

        assertThat(snapshot.totalPublishedFrames()).isEqualTo(1L);
        assertThat(snapshot.successfulPublishedFrames()).isZero();
        assertThat(snapshot.failedPublishedFrames()).isEqualTo(1L);
        assertThat(snapshot.lastPublishLatencyMicros()).isEqualTo(3000L);
        assertThat(snapshot.averagePublishLatencyMs()).isCloseTo(3.0, within(0.001));
    }

    @Test
    @DisplayName("Multiple recorded latencies correctly compute average in milliseconds")
    void multipleRecords_correctlyCalculatesAverage() {
        tracker.recordAttempt();
        tracker.recordSuccess(2000L); // 2.0 ms

        tracker.recordAttempt();
        tracker.recordSuccess(4000L); // 4.0 ms

        KafkaProducerMetrics snapshot = tracker.snapshot();

        assertThat(snapshot.totalPublishedFrames()).isEqualTo(2L);
        assertThat(snapshot.successfulPublishedFrames()).isEqualTo(2L);
        assertThat(snapshot.failedPublishedFrames()).isZero();
        assertThat(snapshot.lastPublishLatencyMicros()).isEqualTo(4000L);
        assertThat(snapshot.averagePublishLatencyMs()).isCloseTo(3.0, within(0.001));
    }

    @Test
    @DisplayName("Negative latency input is safely clamped to zero")
    void negativeLatency_clampedToZero() {
        tracker.recordAttempt();
        tracker.recordSuccess(-500L);

        KafkaProducerMetrics snapshot = tracker.snapshot();

        assertThat(snapshot.lastPublishLatencyMicros()).isZero();
        assertThat(snapshot.averagePublishLatencyMs()).isZero();
    }

    @Test
    @DisplayName("Reset clears all tracked metrics back to empty snapshot")
    void reset_clearsAllMetrics() {
        tracker.recordAttempt();
        tracker.recordSuccess(10000L);
        tracker.recordAttempt();
        tracker.recordFailure(5000L);

        tracker.reset();

        assertThat(tracker.snapshot()).isEqualTo(KafkaProducerMetrics.empty());
    }

    @Test
    @DisplayName("Concurrent multithreaded recording maintains accurate counts")
    void concurrentUpdates_threadSafety() throws InterruptedException {
        int threads = 8;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            executor.submit(
                    () -> {
                        for (int i = 0; i < operationsPerThread; i++) {
                            tracker.recordAttempt();
                            tracker.recordSuccess(1000L);
                        }
                    });
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(finished).isTrue();
        KafkaProducerMetrics snapshot = tracker.snapshot();
        long expectedTotal = (long) threads * operationsPerThread;
        assertThat(snapshot.totalPublishedFrames()).isEqualTo(expectedTotal);
        assertThat(snapshot.successfulPublishedFrames()).isEqualTo(expectedTotal);
        assertThat(snapshot.failedPublishedFrames()).isZero();
        assertThat(snapshot.averagePublishLatencyMs()).isCloseTo(1.0, within(0.001));
    }
}
