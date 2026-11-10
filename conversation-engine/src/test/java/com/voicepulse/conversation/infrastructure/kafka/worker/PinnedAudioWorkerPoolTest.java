package com.voicepulse.conversation.infrastructure.kafka.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.shared.model.CallId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PinnedAudioWorkerPool Concurrency & Affinity Tests")
class PinnedAudioWorkerPoolTest {

    private PinnedAudioWorkerPool pool;

    @BeforeEach
    void setUp() {
        pool = new PinnedAudioWorkerPool(4);
    }

    @AfterEach
    void tearDown() {
        if (pool != null && pool.isRunning()) {
            pool.stop();
        }
    }

    @Test
    @DisplayName(
            "Tasks for the same callId execute on the exact same worker thread (Thread Affinity)")
    void dispatch_tasksForSameCallId_executeOnSameWorkerThread() {
        CallId callId = CallId.generate();
        List<String> threadNames = Collections.synchronizedList(new ArrayList<>());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(
                    pool.dispatch(callId, () -> threadNames.add(Thread.currentThread().getName())));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        assertThat(threadNames).hasSize(20);
        String expectedThread = pool.getAssignedThreadName(callId);
        assertThat(threadNames).containsOnly(expectedThread);
    }

    @Test
    @DisplayName(
            "Concurrent multi-session dispatch preserves monotonic in-order execution per call")
    void dispatch_concurrentCalls_preserveMonotonicInOrderExecutionPerCall() {
        int sessionCount = 10;
        int framesPerSession = 50;

        List<CallId> callIds = new ArrayList<>();
        Map<CallId, List<Integer>> processedSequences = new ConcurrentHashMap<>();

        for (int i = 0; i < sessionCount; i++) {
            CallId callId = CallId.generate();
            callIds.add(callId);
            processedSequences.put(callId, Collections.synchronizedList(new ArrayList<>()));
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        // Interleave dispatch across calls
        for (int frame = 0; frame < framesPerSession; frame++) {
            final int seq = frame;
            for (CallId callId : callIds) {
                futures.add(pool.dispatch(callId, () -> processedSequences.get(callId).add(seq)));
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Verify each individual session observed strictly monotonic 0..49 sequences
        for (CallId callId : callIds) {
            List<Integer> sequences = processedSequences.get(callId);
            assertThat(sequences).hasSize(framesPerSession);
            for (int i = 0; i < framesPerSession; i++) {
                assertThat(sequences.get(i))
                        .as("Sequence mismatch for call %s at index %d", callId, i)
                        .isEqualTo(i);
            }
        }
    }

    @Test
    @DisplayName("Different callIds utilize different worker threads across the pool")
    void dispatch_differentCallIds_utilizeMultipleThreads() {
        Set<String> observedThreads = ConcurrentHashMap.newKeySet();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            CallId callId = CallId.generate();
            futures.add(
                    pool.dispatch(
                            callId, () -> observedThreads.add(Thread.currentThread().getName())));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        assertThat(observedThreads).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("Worker thread handles exceptions gracefully without dying")
    void dispatch_taskThrowsException_workerRemainsOperational() {
        CallId callId = CallId.generate();

        CompletableFuture<Void> failingFuture =
                pool.dispatch(
                        callId,
                        () -> {
                            throw new RuntimeException("Simulated processing error");
                        });

        assertThatThrownBy(failingFuture::join).hasCauseInstanceOf(RuntimeException.class);

        // Next task for same callId must still succeed on the same worker
        CompletableFuture<Void> succeedingFuture = pool.dispatch(callId, () -> {});

        succeedingFuture.join();
        assertThat(succeedingFuture).isCompleted();
    }

    @Test
    @DisplayName("Stop cleanly terminates workers and subsequent dispatch is rejected")
    void stop_terminatesWorkersAndRejectsSubsequentDispatch() {
        assertThat(pool.isRunning()).isTrue();
        pool.stop();
        assertThat(pool.isRunning()).isFalse();

        CallId callId = CallId.generate();
        assertThatThrownBy(() -> pool.dispatch(callId, () -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PinnedAudioWorkerPool is not running");
    }

    @Test
    @DisplayName("Invalid pool size throws IllegalArgumentException")
    void invalidPoolSize_throwsException() {
        assertThatThrownBy(() -> new PinnedAudioWorkerPool(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poolSize must be positive");

        assertThatThrownBy(() -> new PinnedAudioWorkerPool(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poolSize must be positive");
    }

    @Test
    @DisplayName("Dispatch validates null arguments")
    void dispatch_nullArguments_throwsException() {
        CallId callId = CallId.generate();
        assertThatThrownBy(() -> pool.dispatch(null, () -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId cannot be null");

        assertThatThrownBy(() -> pool.dispatch(callId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("task cannot be null");
    }
}
