package com.voicepulse.shared.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CallControlEnvelope Sealed Hierarchy Domain Tests")
class CallControlEnvelopeTest {

    private static final CallId CALL_ID = CallId.generate();
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final AudioFormat FORMAT = AudioFormat.PCM_16KHZ_MONO;

    @Test
    @DisplayName("Should construct CallStarted with valid attributes and enforce constraints")
    void shouldConstructCallStarted() {
        CallStarted event = new CallStarted(CALL_ID, 0L, NOW, FORMAT);

        assertThat(event.callId()).isEqualTo(CALL_ID);
        assertThat(event.sequenceNumber()).isZero();
        assertThat(event.timestamp()).isEqualTo(NOW);
        assertThat(event.format()).isEqualTo(FORMAT);

        assertThatThrownBy(() -> new CallStarted(null, 0L, NOW, FORMAT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");
        assertThatThrownBy(() -> new CallStarted(CALL_ID, -1L, NOW, FORMAT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNumber");
        assertThatThrownBy(() -> new CallStarted(CALL_ID, 0L, null, FORMAT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");
        assertThatThrownBy(() -> new CallStarted(CALL_ID, 0L, NOW, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("format");
    }

    @Test
    @DisplayName("Should construct CallTerminated with valid attributes and enforce constraints")
    void shouldConstructCallTerminated() {
        CallTerminated event = new CallTerminated(CALL_ID, 5L, NOW, "CLIENT_HANGUP");

        assertThat(event.callId()).isEqualTo(CALL_ID);
        assertThat(event.sequenceNumber()).isEqualTo(5L);
        assertThat(event.timestamp()).isEqualTo(NOW);
        assertThat(event.reason()).isEqualTo("CLIENT_HANGUP");

        assertThatThrownBy(() -> new CallTerminated(null, 5L, NOW, "CLIENT_HANGUP"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");
        assertThatThrownBy(() -> new CallTerminated(CALL_ID, -1L, NOW, "CLIENT_HANGUP"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNumber");
        assertThatThrownBy(() -> new CallTerminated(CALL_ID, 5L, null, "CLIENT_HANGUP"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");
        assertThatThrownBy(() -> new CallTerminated(CALL_ID, 5L, NOW, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> new CallTerminated(CALL_ID, 5L, NOW, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason cannot be blank");
    }

    @Test
    @DisplayName(
            "Should construct BargeInInterrupt with valid attributes and secondary constructor")
    void shouldConstructBargeInInterrupt() {
        BargeInInterrupt event = new BargeInInterrupt(CALL_ID, 12L, NOW, 350L);

        assertThat(event.callId()).isEqualTo(CALL_ID);
        assertThat(event.sequenceNumber()).isEqualTo(12L);
        assertThat(event.timestamp()).isEqualTo(NOW);
        assertThat(event.playbackOffsetMs()).isEqualTo(350L);

        BargeInInterrupt defaultOffset = new BargeInInterrupt(CALL_ID, 12L, NOW);
        assertThat(defaultOffset.playbackOffsetMs()).isZero();

        assertThatThrownBy(() -> new BargeInInterrupt(null, 12L, NOW, 350L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");
        assertThatThrownBy(() -> new BargeInInterrupt(CALL_ID, -1L, NOW, 350L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNumber");
        assertThatThrownBy(() -> new BargeInInterrupt(CALL_ID, 12L, null, 350L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");
        assertThatThrownBy(() -> new BargeInInterrupt(CALL_ID, 12L, NOW, -5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("playbackOffsetMs cannot be negative");
    }

    @Test
    @DisplayName(
            "Should construct BackpressureSignal with valid attributes and secondary constructor")
    void shouldConstructBackpressureSignal() {
        BackpressureSignal event = new BackpressureSignal(CALL_ID, 20L, NOW, true, 85);

        assertThat(event.callId()).isEqualTo(CALL_ID);
        assertThat(event.sequenceNumber()).isEqualTo(20L);
        assertThat(event.timestamp()).isEqualTo(NOW);
        assertThat(event.pauseRequested()).isTrue();
        assertThat(event.bufferFillRatioPercent()).isEqualTo(85);

        BackpressureSignal simpleSignal = new BackpressureSignal(CALL_ID, 20L, NOW, false);
        assertThat(simpleSignal.pauseRequested()).isFalse();
        assertThat(simpleSignal.bufferFillRatioPercent()).isZero();

        BackpressureSignal pauseSignal = new BackpressureSignal(CALL_ID, 20L, NOW, true);
        assertThat(pauseSignal.pauseRequested()).isTrue();
        assertThat(pauseSignal.bufferFillRatioPercent()).isEqualTo(100);

        assertThatThrownBy(() -> new BackpressureSignal(null, 20L, NOW, true, 85))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");
        assertThatThrownBy(() -> new BackpressureSignal(CALL_ID, -1L, NOW, true, 85))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNumber");
        assertThatThrownBy(() -> new BackpressureSignal(CALL_ID, 20L, null, true, 85))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");
        assertThatThrownBy(() -> new BackpressureSignal(CALL_ID, 20L, NOW, true, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bufferFillRatioPercent must be between 0 and 100");
        assertThatThrownBy(() -> new BackpressureSignal(CALL_ID, 20L, NOW, true, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bufferFillRatioPercent must be between 0 and 100");
    }

    @Test
    @DisplayName("Should perform exhaustive pattern matching across CallControlEnvelope hierarchy")
    void shouldPerformExhaustivePatternMatching() {
        CallControlEnvelope started = new CallStarted(CALL_ID, 0L, NOW, FORMAT);
        CallControlEnvelope terminated = new CallTerminated(CALL_ID, 1L, NOW, "NORMAL");
        CallControlEnvelope bargeIn = new BargeInInterrupt(CALL_ID, 2L, NOW, 100L);
        CallControlEnvelope backpressure = new BackpressureSignal(CALL_ID, 3L, NOW, true, 90);

        assertThat(describeEnvelope(started)).isEqualTo("STARTED:0");
        assertThat(describeEnvelope(terminated)).isEqualTo("TERMINATED:NORMAL");
        assertThat(describeEnvelope(bargeIn)).isEqualTo("BARGE_IN:100ms");
        assertThat(describeEnvelope(backpressure)).isEqualTo("BACKPRESSURE:PAUSE:90%");
    }

    private String describeEnvelope(CallControlEnvelope envelope) {
        return switch (envelope) {
            case CallStarted s -> "STARTED:" + s.sequenceNumber();
            case CallTerminated t -> "TERMINATED:" + t.reason();
            case BargeInInterrupt b -> "BARGE_IN:" + b.playbackOffsetMs() + "ms";
            case BackpressureSignal bp ->
                    "BACKPRESSURE:"
                            + (bp.pauseRequested() ? "PAUSE" : "RESUME")
                            + ":"
                            + bp.bufferFillRatioPercent()
                            + "%";
        };
    }
}
