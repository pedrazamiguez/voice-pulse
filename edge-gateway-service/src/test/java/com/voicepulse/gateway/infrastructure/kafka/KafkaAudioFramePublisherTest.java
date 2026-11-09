package com.voicepulse.gateway.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.shared.event.AudioChunkEnvelope;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KafkaAudioFramePublisherTest {

    @Mock private KafkaAudioFrameDispatcher dispatcher;

    @Captor private ArgumentCaptor<CallId> callIdCaptor;

    @Captor private ArgumentCaptor<AudioChunkEnvelope> envelopeCaptor;

    private KafkaAudioFramePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaAudioFramePublisher(dispatcher);
    }

    @Test
    @DisplayName("publishAudioChunk converts domain chunk to envelope and delegates to dispatcher")
    void publishAudioChunk_convertsDomainChunkAndDelegates() {
        CallSessionId sessionId = CallSessionId.generate();
        byte[] payload = new byte[640];
        Instant timestamp = Instant.now();

        AudioChunk chunk =
                Instancio.of(AudioChunk.class)
                        .set(field(AudioChunk::sessionId), sessionId)
                        .set(field(AudioChunk::sequenceNumber), 42L)
                        .set(field(AudioChunk::timestamp), timestamp)
                        .set(field(AudioChunk::pcmData), payload)
                        .set(field(AudioChunk::format), AudioFormat.PCM_16KHZ_MONO)
                        .create();

        publisher.publishAudioChunk(chunk);

        verify(dispatcher).dispatch(callIdCaptor.capture(), envelopeCaptor.capture());

        CallId expectedCallId = sessionId.toCallId();
        assertThat(callIdCaptor.getValue()).isEqualTo(expectedCallId);

        AudioChunkEnvelope envelope = envelopeCaptor.getValue();
        assertThat(envelope.callId()).isEqualTo(expectedCallId);
        assertThat(envelope.sequenceNumber()).isEqualTo(42L);
        assertThat(envelope.timestamp()).isEqualTo(timestamp);
        assertThat(envelope.format()).isEqualTo(AudioFormat.PCM_16KHZ_MONO);
        assertThat(envelope.payload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("publishAudioFrame creates 16 kHz mono envelope and delegates to dispatcher")
    void publishAudioFrame_createsEnvelopeAndDelegates() {
        CallId callId = CallId.generate();
        byte[] payload = new byte[320];
        long sequenceNumber = 10L;

        publisher.publishAudioFrame(callId, sequenceNumber, payload);

        verify(dispatcher).dispatch(callIdCaptor.capture(), envelopeCaptor.capture());

        assertThat(callIdCaptor.getValue()).isEqualTo(callId);

        AudioChunkEnvelope envelope = envelopeCaptor.getValue();
        assertThat(envelope.callId()).isEqualTo(callId);
        assertThat(envelope.sequenceNumber()).isEqualTo(sequenceNumber);
        assertThat(envelope.format()).isEqualTo(AudioFormat.PCM_16KHZ_MONO);
        assertThat(envelope.payload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("Null arguments throw NullPointerException")
    void nullArguments_throwNullPointerException() {
        CallId callId = CallId.generate();
        byte[] payload = new byte[320];

        assertThatThrownBy(() -> publisher.publishAudioChunk(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("chunk cannot be null");

        assertThatThrownBy(() -> publisher.publishAudioFrame(null, 1L, payload))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("callId cannot be null");

        assertThatThrownBy(() -> publisher.publishAudioFrame(callId, 1L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("audioPayload cannot be null");
    }

    @Test
    @DisplayName("getMetrics delegates to dispatcher metrics snapshot")
    void getMetrics_delegatesToDispatcher() {
        KafkaProducerMetrics metrics = new KafkaProducerMetrics(10L, 9L, 1L, 1500L, 1.5);
        when(dispatcher.getMetrics()).thenReturn(metrics);

        assertThat(publisher.getMetrics()).isEqualTo(metrics);
        verify(dispatcher).getMetrics();
    }
}
