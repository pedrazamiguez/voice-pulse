package com.voicepulse.conversation.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordingProcessAudioChunkUseCaseTest {

    private RecordingProcessAudioChunkUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RecordingProcessAudioChunkUseCase();
    }

    @Test
    @DisplayName("Recorded chunks are captured and queried by CallId")
    void recordsChunks_andQueriesByCallId() {
        CallId callId1 = CallId.generate();
        CallId callId2 = CallId.generate();

        byte[] payload1 = SyntheticAudioStreamGenerator.generatePayload(callId1, 0L);
        byte[] payload2 = SyntheticAudioStreamGenerator.generatePayload(callId2, 0L);

        AudioChunk chunk1 =
                new AudioChunk(
                        callId1.toSessionId(),
                        0L,
                        Instant.now(),
                        payload1,
                        AudioFormat.PCM_16KHZ_MONO);
        AudioChunk chunk2 =
                new AudioChunk(
                        callId2.toSessionId(),
                        0L,
                        Instant.now(),
                        payload2,
                        AudioFormat.PCM_16KHZ_MONO);

        useCase.processAudioChunk(chunk1);
        useCase.processAudioChunk(chunk2);

        assertThat(useCase.getTotalRecordedCount()).isEqualTo(2);
        assertThat(useCase.getChunksFor(callId1)).hasSize(1);
        assertThat(useCase.getChunksFor(callId2)).hasSize(1);
    }

    @Test
    @DisplayName(
            "Monotonic sequence verification succeeds for contiguous sequence and fails on gaps")
    void verifiesMonotonicSequence() {
        CallId callId = CallId.generate();

        for (long seq = 0; seq < 5; seq++) {
            byte[] payload = SyntheticAudioStreamGenerator.generatePayload(callId, seq);
            useCase.processAudioChunk(
                    new AudioChunk(
                            callId.toSessionId(),
                            seq,
                            Instant.now(),
                            payload,
                            AudioFormat.PCM_16KHZ_MONO));
        }

        assertThat(useCase.verifyStrictMonotonicSequence(callId, 0L, 4L)).isTrue();
        assertThat(useCase.verifyStrictMonotonicSequence(callId, 0L, 5L)).isFalse();
        assertThat(useCase.verifyStrictMonotonicSequence(callId, 1L, 4L)).isFalse();
    }

    @Test
    @DisplayName("Payload integrity passes when payloads match generator and fails when modified")
    void verifiesPayloadIntegrity() {
        CallId callId = CallId.generate();

        byte[] payload = SyntheticAudioStreamGenerator.generatePayload(callId, 0L);
        useCase.processAudioChunk(
                new AudioChunk(
                        callId.toSessionId(),
                        0L,
                        Instant.now(),
                        payload,
                        AudioFormat.PCM_16KHZ_MONO));

        assertThat(useCase.verifyPayloadIntegrity(callId)).isTrue();

        RecordingProcessAudioChunkUseCase corruptUseCase = new RecordingProcessAudioChunkUseCase();
        byte[] corrupted = new byte[640];
        corruptUseCase.processAudioChunk(
                new AudioChunk(
                        callId.toSessionId(),
                        0L,
                        Instant.now(),
                        corrupted,
                        AudioFormat.PCM_16KHZ_MONO));

        assertThat(corruptUseCase.verifyPayloadIntegrity(callId)).isFalse();
    }

    @Test
    @DisplayName("Single thread execution verifies all chunks were processed on one thread")
    void verifiesSingleThreadExecution() {
        CallId callId = CallId.generate();

        byte[] payload = SyntheticAudioStreamGenerator.generatePayload(callId, 0L);
        useCase.processAudioChunk(
                new AudioChunk(
                        callId.toSessionId(),
                        0L,
                        Instant.now(),
                        payload,
                        AudioFormat.PCM_16KHZ_MONO));

        assertThat(useCase.verifySingleThreadExecution(callId)).isTrue();
    }

    @Test
    @DisplayName("Reset clears all recorded chunks and count")
    void reset_clearsState() {
        CallId callId = CallId.generate();
        byte[] payload = SyntheticAudioStreamGenerator.generatePayload(callId, 0L);
        useCase.processAudioChunk(
                new AudioChunk(
                        callId.toSessionId(),
                        0L,
                        Instant.now(),
                        payload,
                        AudioFormat.PCM_16KHZ_MONO));

        assertThat(useCase.getTotalRecordedCount()).isEqualTo(1);
        useCase.reset();
        assertThat(useCase.getTotalRecordedCount()).isZero();
        assertThat(useCase.getChunksFor(callId)).isEmpty();
    }

    @Test
    @DisplayName("RecordedAudioChunk contract verifies equality, immutability, and toString")
    void recordedAudioChunk_contractVerification() {
        CallId callId = CallId.generate();
        Instant now = Instant.now();
        byte[] payload = SyntheticAudioStreamGenerator.generatePayload(callId, 0L);
        AudioChunk chunk =
                new AudioChunk(callId.toSessionId(), 0L, now, payload, AudioFormat.PCM_16KHZ_MONO);

        RecordedAudioChunk recorded1 = RecordedAudioChunk.from(chunk, now, "worker-1");
        RecordedAudioChunk recorded2 = RecordedAudioChunk.from(chunk, now, "worker-1");

        assertThat(recorded1).isEqualTo(recorded2);
        assertThat(recorded1.hashCode()).isEqualTo(recorded2.hashCode());
        assertThat(recorded1.toString()).contains("RecordedAudioChunk");
        assertThat(recorded1.pcmData()).isEqualTo(payload);

        // Immutability check
        recorded1.pcmData()[0] = (byte) 0xFF;
        assertThat(recorded1.pcmData()).isEqualTo(payload);
    }
}
