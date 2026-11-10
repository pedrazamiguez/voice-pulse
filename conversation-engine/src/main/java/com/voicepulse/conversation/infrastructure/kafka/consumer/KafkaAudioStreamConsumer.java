package com.voicepulse.conversation.infrastructure.kafka.consumer;

import com.voicepulse.conversation.domain.ports.inbound.ProcessAudioChunkUseCase;
import com.voicepulse.conversation.infrastructure.kafka.sequence.MonotonicSequenceVerifier;
import com.voicepulse.conversation.infrastructure.kafka.sequence.SequenceValidationResult;
import com.voicepulse.conversation.infrastructure.kafka.worker.PinnedAudioWorkerPool;
import com.voicepulse.shared.event.AudioChunkEnvelope;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Primary inbound adapter consuming audio chunk envelopes from {@code telephony.calls.raw}.
 *
 * <p>Validates monotonic sequence continuity, handles dropped or duplicate frames, and dispatches
 * valid frames to {@link PinnedAudioWorkerPool} for sequential processing by {@link
 * ProcessAudioChunkUseCase}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "voicepulse.conversation.kafka",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class KafkaAudioStreamConsumer {

    private final @NonNull MonotonicSequenceVerifier sequenceVerifier;
    private final @NonNull PinnedAudioWorkerPool workerPool;
    private final @NonNull ProcessAudioChunkUseCase processAudioChunkUseCase;

    private final AtomicInteger processedChunksCount = new AtomicInteger(0);

    @KafkaListener(
            id = "audioStreamListener",
            topics = "${voicepulse.kafka.topics.raw.name}",
            groupId = "${voicepulse.conversation.kafka.consumer-group}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onAudioChunk(
            @Payload AudioChunkEnvelope envelope,
            @Header(KafkaHeaders.RECEIVED_KEY) String callIdHeader,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        Objects.requireNonNull(envelope, "envelope cannot be null");
        processedChunksCount.incrementAndGet();

        CallId callId = envelope.callId();
        long sequenceNumber = envelope.sequenceNumber();

        log.debug(
                "Ingested audio chunk: callId={}, seq={}, partition={}, offset={}",
                callId,
                sequenceNumber,
                partition,
                offset);

        SequenceValidationResult validationResult = sequenceVerifier.verify(callId, sequenceNumber);
        if (validationResult.shouldDispatch()) {
            AudioChunk chunk = envelope.toAudioChunk();
            workerPool.dispatch(callId, () -> processAudioChunkUseCase.processAudioChunk(chunk));
        } else {
            log.warn(
                    "Dropping audio chunk for callId={}: seq={}, result={}",
                    callId,
                    sequenceNumber,
                    validationResult);
        }
    }

    public int getProcessedChunksCount() {
        return processedChunksCount.get();
    }
}
