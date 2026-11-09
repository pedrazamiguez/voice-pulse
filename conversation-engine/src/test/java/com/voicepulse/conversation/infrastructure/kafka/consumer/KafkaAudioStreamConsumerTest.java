package com.voicepulse.conversation.infrastructure.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.conversation.domain.ports.inbound.ProcessAudioChunkUseCase;
import com.voicepulse.conversation.infrastructure.kafka.sequence.MonotonicSequenceVerifier;
import com.voicepulse.conversation.infrastructure.kafka.sequence.SequenceValidationResult;
import com.voicepulse.conversation.infrastructure.kafka.worker.PinnedAudioWorkerPool;
import com.voicepulse.shared.event.AudioChunkEnvelope;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaAudioStreamConsumer Unit Tests")
class KafkaAudioStreamConsumerTest {

    @Mock private MonotonicSequenceVerifier sequenceVerifier;

    @Mock private PinnedAudioWorkerPool workerPool;

    @Mock private ProcessAudioChunkUseCase processAudioChunkUseCase;

    @InjectMocks private KafkaAudioStreamConsumer consumer;

    @Captor private ArgumentCaptor<Runnable> taskCaptor;

    @Captor private ArgumentCaptor<AudioChunk> chunkCaptor;

    private AudioChunkEnvelope createEnvelope(CallId callId, long sequenceNumber) {
        return new AudioChunkEnvelope(
                callId, sequenceNumber, Instant.now(), AudioFormat.PCM_16KHZ_MONO, new byte[640]);
    }

    @Test
    @DisplayName("Valid sequence frame is dispatched to pinned worker pool and domain use case")
    void onAudioChunk_validSequence_dispatchesToWorkerPoolAndUseCase() {
        CallId callId = CallId.generate();
        AudioChunkEnvelope envelope = createEnvelope(callId, 0L);

        when(sequenceVerifier.verify(callId, 0L))
                .thenReturn(new SequenceValidationResult.Valid(callId, 0L));

        consumer.onAudioChunk(envelope, callId.toString(), 0, 10L);

        assertThat(consumer.getProcessedChunksCount()).isEqualTo(1);
        verify(workerPool).dispatch(eq(callId), taskCaptor.capture());

        // Execute task dispatched to the worker
        taskCaptor.getValue().run();
        verify(processAudioChunkUseCase).processAudioChunk(chunkCaptor.capture());

        AudioChunk dispatchedChunk = chunkCaptor.getValue();
        assertThat(dispatchedChunk.sessionId().toCallId()).isEqualTo(callId);
        assertThat(dispatchedChunk.sequenceNumber()).isZero();
    }

    @Test
    @DisplayName("Sequence gap detected frame is still dispatched to worker pool and use case")
    void onAudioChunk_gapDetected_dispatchesToWorkerPoolAndUseCase() {
        CallId callId = CallId.generate();
        AudioChunkEnvelope envelope = createEnvelope(callId, 5L);

        when(sequenceVerifier.verify(callId, 5L))
                .thenReturn(new SequenceValidationResult.GapDetected(callId, 5L, 2L, 3L));

        consumer.onAudioChunk(envelope, callId.toString(), 1, 15L);

        assertThat(consumer.getProcessedChunksCount()).isEqualTo(1);
        verify(workerPool).dispatch(eq(callId), taskCaptor.capture());

        taskCaptor.getValue().run();
        verify(processAudioChunkUseCase).processAudioChunk(chunkCaptor.capture());
        assertThat(chunkCaptor.getValue().sequenceNumber()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Duplicate frame is dropped without worker pool dispatch or use case processing")
    void onAudioChunk_duplicateSequence_dropsFrameWithoutDispatch() {
        CallId callId = CallId.generate();
        AudioChunkEnvelope envelope = createEnvelope(callId, 1L);

        when(sequenceVerifier.verify(callId, 1L))
                .thenReturn(new SequenceValidationResult.Duplicate(callId, 1L));

        consumer.onAudioChunk(envelope, callId.toString(), 0, 12L);

        assertThat(consumer.getProcessedChunksCount()).isEqualTo(1);
        verify(workerPool, never()).dispatch(eq(callId), any());
        verify(processAudioChunkUseCase, never()).processAudioChunk(any());
    }

    @Test
    @DisplayName(
            "Out-of-order frame is dropped without worker pool dispatch or use case processing")
    void onAudioChunk_outOfOrderSequence_dropsFrameWithoutDispatch() {
        CallId callId = CallId.generate();
        AudioChunkEnvelope envelope = createEnvelope(callId, 2L);

        when(sequenceVerifier.verify(callId, 2L))
                .thenReturn(new SequenceValidationResult.OutOfOrder(callId, 2L, 5L));

        consumer.onAudioChunk(envelope, callId.toString(), 0, 14L);

        assertThat(consumer.getProcessedChunksCount()).isEqualTo(1);
        verify(workerPool, never()).dispatch(eq(callId), any());
        verify(processAudioChunkUseCase, never()).processAudioChunk(any());
    }

    @Test
    @DisplayName("Null envelope throws NullPointerException")
    void onAudioChunk_nullEnvelope_throwsException() {
        assertThatThrownBy(() -> consumer.onAudioChunk(null, "key", 0, 0L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("envelope cannot be null");
    }
}
