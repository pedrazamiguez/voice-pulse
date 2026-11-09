package com.voicepulse.gateway.infrastructure.kafka;

import com.voicepulse.gateway.domain.ports.outbound.AudioFramePublisherPort;
import com.voicepulse.shared.event.AudioChunkEnvelope;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Secondary outbound infrastructure adapter implementing {@link AudioFramePublisherPort}.
 *
 * <p>Translates domain entities ({@link AudioChunk}, {@link CallId}) into {@link
 * AudioChunkEnvelope} instances and delegates asynchronous dispatch to {@link
 * KafkaAudioFrameDispatcher}.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "voicepulse.gateway",
        name = "audio-publisher",
        havingValue = "kafka",
        matchIfMissing = true)
public class KafkaAudioFramePublisher implements AudioFramePublisherPort {

    private final @NonNull KafkaAudioFrameDispatcher dispatcher;

    @Override
    public void publishAudioChunk(AudioChunk chunk) {
        Objects.requireNonNull(chunk, "chunk cannot be null");
        dispatcher.dispatch(chunk.sessionId().toCallId(), AudioChunkEnvelope.from(chunk));
    }

    @Override
    public void publishAudioFrame(CallId callId, long sequenceNumber, byte[] audioPayload) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(audioPayload, "audioPayload cannot be null");
        AudioChunkEnvelope envelope =
                new AudioChunkEnvelope(
                        callId,
                        sequenceNumber,
                        Instant.now(),
                        AudioFormat.PCM_16KHZ_MONO,
                        audioPayload);
        dispatcher.dispatch(callId, envelope);
    }

    /**
     * Retrieves telemetry metrics from the underlying dispatcher.
     *
     * @return current metrics snapshot
     */
    public KafkaProducerMetrics getMetrics() {
        return dispatcher.getMetrics();
    }
}
