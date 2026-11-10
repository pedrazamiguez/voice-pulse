package com.voicepulse.conversation.infrastructure.kafka.worker;

import com.voicepulse.shared.model.CallId;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Pinned thread pool executor that guarantees strict sequential FIFO execution of audio chunks for
 * each individual call session.
 *
 * <p>Pins each {@link CallId} deterministically to an isolated single-thread executor via hash
 * striping ({@code Math.floorMod(callId.hashCode(), poolSize)}). This eliminates cross-thread race
 * conditions and preserves monotonic temporal ordering without coarse global locking.
 */
@Slf4j
public class PinnedAudioWorkerPool implements SmartLifecycle, AutoCloseable {

    private final int poolSize;
    private final ExecutorService[] workers;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PinnedAudioWorkerPool(int poolSize) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive: " + poolSize);
        }
        this.poolSize = poolSize;
        this.workers = new ExecutorService[poolSize];
        initWorkers();
    }

    private synchronized void initWorkers() {
        if (running.compareAndSet(false, true)) {
            for (int i = 0; i < poolSize; i++) {
                final int workerId = i;
                ThreadFactory factory =
                        runnable -> {
                            Thread thread = new Thread(runnable);
                            thread.setName("audio-worker-" + workerId);
                            thread.setDaemon(true);
                            return thread;
                        };
                workers[i] = Executors.newSingleThreadExecutor(factory);
            }
            log.info("Initialized PinnedAudioWorkerPool with {} worker threads", poolSize);
        }
    }

    /**
     * Dispatches a task to the pinned worker thread assigned to the specified call ID.
     *
     * @param callId call session identifier used for thread pinning
     * @param task sequential processing task to execute
     * @return future completing when the task finishes execution
     */
    public CompletableFuture<Void> dispatch(CallId callId, Runnable task) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(task, "task cannot be null");

        if (!running.get()) {
            throw new IllegalStateException("PinnedAudioWorkerPool is not running");
        }

        int index = getWorkerIndex(callId);
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        task.run();
                    } catch (Throwable t) {
                        log.error("Uncaught exception executing task for callId={}", callId, t);
                        throw t;
                    }
                },
                workers[index]);
    }

    /**
     * Returns the worker index assigned to the given call ID.
     *
     * @param callId call session identifier
     * @return worker index in range {@code [0, poolSize - 1]}
     */
    public int getWorkerIndex(CallId callId) {
        Objects.requireNonNull(callId, "callId cannot be null");
        return Math.floorMod(callId.hashCode(), poolSize);
    }

    /**
     * Returns the name of the worker thread pinned for the given call ID.
     *
     * @param callId call session identifier
     * @return thread name prefix followed by worker index
     */
    public String getAssignedThreadName(CallId callId) {
        return "audio-worker-" + getWorkerIndex(callId);
    }

    /**
     * Returns the configured worker pool capacity.
     *
     * @return count of striped worker threads
     */
    public int poolSize() {
        return poolSize;
    }

    @Override
    public synchronized void start() {
        initWorkers();
    }

    @Override
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Shutting down PinnedAudioWorkerPool...");
            for (int i = 0; i < poolSize; i++) {
                if (workers[i] != null) {
                    workers[i].shutdown();
                    try {
                        if (!workers[i].awaitTermination(5, TimeUnit.SECONDS)) {
                            workers[i].shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        workers[i].shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                }
            }
            log.info("PinnedAudioWorkerPool shut down successfully");
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public void close() {
        stop();
    }
}
