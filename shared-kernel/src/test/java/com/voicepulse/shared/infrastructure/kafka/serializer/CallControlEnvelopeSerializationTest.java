package com.voicepulse.shared.infrastructure.kafka.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.shared.event.BackpressureSignal;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.event.CallStarted;
import com.voicepulse.shared.event.CallTerminated;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.apache.kafka.common.errors.SerializationException;
import org.instancio.Instancio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CallControlEnvelope Polymorphic JSON Serialization Tests")
class CallControlEnvelopeSerializationTest {

    private static final String TOPIC = "telephony.calls.control";
    private static final CallId CALL_ID = CallId.generate();
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final AudioFormat FORMAT = AudioFormat.PCM_16KHZ_MONO;

    private CallControlEnvelopeSerializer serializer;
    private CallControlEnvelopeDeserializer deserializer;

    @BeforeEach
    void setUp() {
        serializer = new CallControlEnvelopeSerializer();
        deserializer = new CallControlEnvelopeDeserializer();
    }

    @AfterEach
    void tearDown() {
        serializer.close();
        deserializer.close();
    }

    @Test
    @DisplayName("Should serialize and deserialize CallStarted event with polymorphic fidelity")
    void shouldRoundTripCallStarted() {
        CallStarted event =
                Instancio.of(CallStarted.class)
                        .set(field(CallStarted::callId), CALL_ID)
                        .set(field(CallStarted::sequenceNumber), 0L)
                        .set(field(CallStarted::timestamp), NOW)
                        .set(field(CallStarted::format), FORMAT)
                        .create();

        byte[] bytes = serializer.serialize(TOPIC, event);
        assertThat(bytes).isNotNull();

        CallControlEnvelope result = deserializer.deserialize(TOPIC, bytes);
        assertThat(result).isInstanceOf(CallStarted.class);
        CallStarted started = (CallStarted) result;

        assertThat(started.callId()).isEqualTo(CALL_ID);
        assertThat(started.sequenceNumber()).isZero();
        assertThat(started.timestamp()).isEqualTo(NOW);
        assertThat(started.format()).isEqualTo(FORMAT);
    }

    @Test
    @DisplayName("Should serialize and deserialize CallTerminated event with polymorphic fidelity")
    void shouldRoundTripCallTerminated() {
        CallTerminated event =
                Instancio.of(CallTerminated.class)
                        .set(field(CallTerminated::callId), CALL_ID)
                        .set(field(CallTerminated::sequenceNumber), 10L)
                        .set(field(CallTerminated::timestamp), NOW)
                        .set(field(CallTerminated::reason), "CLIENT_HANGUP")
                        .create();

        byte[] bytes = serializer.serialize(TOPIC, event);
        assertThat(bytes).isNotNull();

        CallControlEnvelope result = deserializer.deserialize(TOPIC, bytes);
        assertThat(result).isInstanceOf(CallTerminated.class);
        CallTerminated terminated = (CallTerminated) result;

        assertThat(terminated.callId()).isEqualTo(CALL_ID);
        assertThat(terminated.sequenceNumber()).isEqualTo(10L);
        assertThat(terminated.timestamp()).isEqualTo(NOW);
        assertThat(terminated.reason()).isEqualTo("CLIENT_HANGUP");
    }

    @Test
    @DisplayName(
            "Should serialize and deserialize BargeInInterrupt event with polymorphic fidelity")
    void shouldRoundTripBargeInInterrupt() {
        BargeInInterrupt event =
                Instancio.of(BargeInInterrupt.class)
                        .set(field(BargeInInterrupt::callId), CALL_ID)
                        .set(field(BargeInInterrupt::sequenceNumber), 15L)
                        .set(field(BargeInInterrupt::timestamp), NOW)
                        .set(field(BargeInInterrupt::playbackOffsetMs), 420L)
                        .create();

        byte[] bytes = serializer.serialize(TOPIC, event);
        assertThat(bytes).isNotNull();

        CallControlEnvelope result = deserializer.deserialize(TOPIC, bytes);
        assertThat(result).isInstanceOf(BargeInInterrupt.class);
        BargeInInterrupt bargeIn = (BargeInInterrupt) result;

        assertThat(bargeIn.callId()).isEqualTo(CALL_ID);
        assertThat(bargeIn.sequenceNumber()).isEqualTo(15L);
        assertThat(bargeIn.timestamp()).isEqualTo(NOW);
        assertThat(bargeIn.playbackOffsetMs()).isEqualTo(420L);
    }

    @Test
    @DisplayName(
            "Should serialize and deserialize BackpressureSignal event with polymorphic fidelity")
    void shouldRoundTripBackpressureSignal() {
        BackpressureSignal event =
                Instancio.of(BackpressureSignal.class)
                        .set(field(BackpressureSignal::callId), CALL_ID)
                        .set(field(BackpressureSignal::sequenceNumber), 25L)
                        .set(field(BackpressureSignal::timestamp), NOW)
                        .set(field(BackpressureSignal::pauseRequested), true)
                        .set(field(BackpressureSignal::bufferFillRatioPercent), 80)
                        .create();

        byte[] bytes = serializer.serialize(TOPIC, event);
        assertThat(bytes).isNotNull();

        CallControlEnvelope result = deserializer.deserialize(TOPIC, bytes);
        assertThat(result).isInstanceOf(BackpressureSignal.class);
        BackpressureSignal backpressure = (BackpressureSignal) result;

        assertThat(backpressure.callId()).isEqualTo(CALL_ID);
        assertThat(backpressure.sequenceNumber()).isEqualTo(25L);
        assertThat(backpressure.timestamp()).isEqualTo(NOW);
        assertThat(backpressure.pauseRequested()).isTrue();
        assertThat(backpressure.bufferFillRatioPercent()).isEqualTo(80);
    }

    @Test
    @DisplayName("Should return null when serializing or deserializing null/empty data")
    void shouldHandleNullAndEmpty() {
        assertThat(serializer.serialize(TOPIC, null)).isNull();
        assertThat(deserializer.deserialize(TOPIC, null)).isNull();
        assertThat(deserializer.deserialize(TOPIC, new byte[0])).isNull();
    }

    @Test
    @DisplayName("Should reject malformed JSON with SerializationException")
    void shouldRejectMalformedJson() {
        byte[] malformed = "{ invalid json payload ".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> deserializer.deserialize(TOPIC, malformed))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Failed to deserialize CallControlEnvelope JSON payload");
    }

    @Test
    @DisplayName("Should reject JSON payload missing 'type' discriminator")
    void shouldRejectMissingTypeDiscriminator() {
        String json =
                "{\"callId\":\""
                        + CALL_ID
                        + "\",\"sequenceNumber\":0,\"timestamp\":\""
                        + NOW
                        + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> deserializer.deserialize(TOPIC, bytes))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Missing 'type' discriminator");
    }

    @Test
    @DisplayName("Should reject JSON payload with unknown 'type' discriminator")
    void shouldRejectUnknownTypeDiscriminator() {
        String json =
                "{\"type\":\"UNKNOWN_SIGNAL\",\"callId\":\""
                        + CALL_ID
                        + "\",\"sequenceNumber\":0,\"timestamp\":\""
                        + NOW
                        + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> deserializer.deserialize(TOPIC, bytes))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Unknown CallControlEnvelope type: UNKNOWN_SIGNAL");
    }
}
