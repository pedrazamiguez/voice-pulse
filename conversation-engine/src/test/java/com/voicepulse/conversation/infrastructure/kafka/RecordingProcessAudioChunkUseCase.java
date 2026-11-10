package com.voicepulse.conversation.infrastructure.kafka;

import com.voicepulse.conversation.domain.ports.inbound.ProcessAudioChunkUseCase;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

/**
 * Thread-safe test double implementation of {@link ProcessAudioChunkUseCase} capturing ingested
 * audio chunks for latency, integrity, and thread-affinity assertions.
 */
@TestComponent
@Primary
public class RecordingProcessAudioChunkUseCase implements ProcessAudioChunkUseCase {

    private final ConcurrentLinkedQueue<RecordedAudioChunk> recordedChunks =
            new ConcurrentLinkedQueue<>();
    private final AtomicInteger totalCount = new AtomicInteger(0);

    @Override
    public void processAudioChunk(AudioChunk chunk) {
        Objects.requireNonNull(chunk, "chunk cannot be null");
        Instant now = Instant.now();
        String threadName = Thread.currentThread().getName();

        RecordedAudioChunk recorded = RecordedAudioChunk.from(chunk, now, threadName);
        recordedChunks.add(recorded);
        totalCount.incrementAndGet();
    }

    /**
     * Returns total number of frames recorded across all sessions.
     *
     * @return count of processed audio chunks
     */
    public int getTotalRecordedCount() {
        return totalCount.get();
    }

    /**
     * Retrieves all recorded chunks for a specific call identifier, ordered by received timestamp.
     *
     * @param callId call identifier
     * @return list of recorded audio chunks
     */
    public List<RecordedAudioChunk> getChunksFor(CallId callId) {
        Objects.requireNonNull(callId, "callId cannot be null");
        return recordedChunks.stream()
                .filter(chunk -> chunk.sessionId().equals(callId.toSessionId()))
                .sorted(Comparator.comparingLong(RecordedAudioChunk::sequenceNumber))
                .toList();
    }

    /**
     * Computes average end-to-end transport latency in milliseconds for a call session.
     *
     * @param callId call identifier
     * @return average latency in milliseconds, or 0.0 if no chunks recorded
     */
    public double getAverageLatencyMs(CallId callId) {
        List<RecordedAudioChunk> chunks = getChunksFor(callId);
        if (chunks.isEmpty()) {
            return 0.0;
        }
        return chunks.stream().mapToLong(RecordedAudioChunk::latencyMillis).average().orElse(0.0);
    }

    /**
     * Asserts that all recorded chunks for a call session form a strictly monotonic sequence
     * without gaps or duplicate sequence numbers.
     *
     * @param callId call identifier
     * @param expectedStart inclusive starting sequence number
     * @param expectedEnd inclusive ending sequence number
     * @return true if strictly monotonic
     */
    public boolean verifyStrictMonotonicSequence(
            CallId callId, long expectedStart, long expectedEnd) {
        List<RecordedAudioChunk> chunks = getChunksFor(callId);
        long expectedCount = (expectedEnd - expectedStart) + 1L;
        if (chunks.size() != expectedCount) {
            return false;
        }

        for (int i = 0; i < chunks.size(); i++) {
            long expectedSeq = expectedStart + i;
            if (chunks.get(i).sequenceNumber() != expectedSeq) {
                return false;
            }
        }
        return true;
    }

    /**
     * Asserts that all recorded chunks for a call session match the synthetic pattern bit-for-bit.
     *
     * @param callId call identifier
     * @return true if all payloads match expected pattern
     */
    public boolean verifyPayloadIntegrity(CallId callId) {
        List<RecordedAudioChunk> chunks = getChunksFor(callId);
        if (chunks.isEmpty()) {
            return false;
        }

        for (RecordedAudioChunk chunk : chunks) {
            if (!SyntheticAudioStreamGenerator.matchesExpected(
                    callId, chunk.sequenceNumber(), chunk.pcmData())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Asserts that all recorded chunks for a call session were dispatched to and executed by the
     * exact same pinned worker thread.
     *
     * @param callId call identifier
     * @return true if executed on a single worker thread
     */
    public boolean verifySingleThreadExecution(CallId callId) {
        List<RecordedAudioChunk> chunks = getChunksFor(callId);
        if (chunks.isEmpty()) {
            return false;
        }

        Set<String> threads =
                chunks.stream().map(RecordedAudioChunk::threadName).collect(Collectors.toSet());
        return threads.size() == 1;
    }

    /** Resets all internal recorded states. */
    public void reset() {
        recordedChunks.clear();
        totalCount.set(0);
    }
}
