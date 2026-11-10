package com.voicepulse.conversation.infrastructure.kafka;

import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable test fixture recording an ingested audio chunk along with timing and thread telemetry.
 */
public record RecordedAudioChunk(
        CallSessionId sessionId,
        long sequenceNumber,
        Instant publishedTimestamp,
        Instant receivedTimestamp,
        byte[] pcmData,
        String threadName,
        long latencyMillis) {

    public RecordedAudioChunk {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        Objects.requireNonNull(publishedTimestamp, "publishedTimestamp cannot be null");
        Objects.requireNonNull(receivedTimestamp, "receivedTimestamp cannot be null");
        Objects.requireNonNull(pcmData, "pcmData cannot be null");
        Objects.requireNonNull(threadName, "threadName cannot be null");
        pcmData = pcmData.clone();
    }

    /**
     * Factory method creating a {@link RecordedAudioChunk} from an incoming {@link AudioChunk}.
     *
     * @param chunk source domain audio chunk
     * @param receivedTimestamp instant when chunk reached the test receiver
     * @param threadName name of thread executing the use case
     * @return populated recorded audio chunk
     */
    public static RecordedAudioChunk from(
            AudioChunk chunk, Instant receivedTimestamp, String threadName) {
        Objects.requireNonNull(chunk, "chunk cannot be null");
        Objects.requireNonNull(receivedTimestamp, "receivedTimestamp cannot be null");
        Objects.requireNonNull(threadName, "threadName cannot be null");

        long latency =
                Math.max(0L, Duration.between(chunk.timestamp(), receivedTimestamp).toMillis());
        return new RecordedAudioChunk(
                chunk.sessionId(),
                chunk.sequenceNumber(),
                chunk.timestamp(),
                receivedTimestamp,
                chunk.pcmData(),
                threadName,
                latency);
    }

    @Override
    public byte[] pcmData() {
        return pcmData.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RecordedAudioChunk that)) {
            return false;
        }
        return sequenceNumber == that.sequenceNumber
                && latencyMillis == that.latencyMillis
                && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(publishedTimestamp, that.publishedTimestamp)
                && Objects.equals(receivedTimestamp, that.receivedTimestamp)
                && Arrays.equals(pcmData, that.pcmData)
                && Objects.equals(threadName, that.threadName);
    }

    @Override
    public int hashCode() {
        int result =
                Objects.hash(
                        sessionId,
                        sequenceNumber,
                        publishedTimestamp,
                        receivedTimestamp,
                        threadName,
                        latencyMillis);
        result = 31 * result + Arrays.hashCode(pcmData);
        return result;
    }

    @Override
    public String toString() {
        return "RecordedAudioChunk[sessionId="
                + sessionId
                + ", seq="
                + sequenceNumber
                + ", thread="
                + threadName
                + ", latencyMs="
                + latencyMillis
                + ", bytes="
                + pcmData.length
                + "]";
    }
}
