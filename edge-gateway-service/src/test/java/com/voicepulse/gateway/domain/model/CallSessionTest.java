package com.voicepulse.gateway.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.gateway.domain.exception.IllegalSessionStateException;
import com.voicepulse.gateway.domain.exception.InvalidAudioFrameException;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallSessionTest {

    private final CallSessionId sessionId = CallSessionId.generate();
    private final Instant startTime = Instant.parse("2026-11-08T10:00:00Z");

    @Test
    @DisplayName("start creates session in Connected state with initial sequence -1")
    void startSessionInitialState() {
        CallSession session = CallSession.start(sessionId, startTime);

        assertThat(session.id()).isEqualTo(sessionId);
        assertThat(session.lastSequenceNumber()).isEqualTo(-1L);
        assertThat(session.createdAt()).isEqualTo(startTime);
        assertThat(session.lastActivityAt()).isEqualTo(startTime);
        assertThat(session.state()).isInstanceOf(CallSessionState.Connected.class);

        CallSessionState.Connected connected = (CallSessionState.Connected) session.state();
        assertThat(connected.connectedAt()).isEqualTo(startTime);
    }

    @Test
    @DisplayName("first accepted chunk transitions session to Streaming state")
    void firstChunkTransitionsToStreaming() {
        CallSession session = CallSession.start(sessionId, startTime);
        Instant chunkTime = startTime.plusMillis(20);
        AudioChunk chunk =
                new AudioChunk(sessionId, 0L, chunkTime, new byte[640], AudioFormat.PCM_16KHZ_MONO);

        session.acceptChunk(chunk);

        assertThat(session.lastSequenceNumber()).isZero();
        assertThat(session.lastActivityAt()).isEqualTo(chunkTime);
        assertThat(session.state()).isInstanceOf(CallSessionState.Streaming.class);

        CallSessionState.Streaming streaming = (CallSessionState.Streaming) session.state();
        assertThat(streaming.startedStreamingAt()).isEqualTo(chunkTime);
        assertThat(streaming.framesReceived()).isEqualTo(1L);
    }

    @Test
    @DisplayName("subsequent chunks update frame count and sequence monotonically")
    void subsequentChunksIncrementFrames() {
        CallSession session = CallSession.start(sessionId, startTime);

        session.acceptChunk(
                new AudioChunk(
                        sessionId,
                        0L,
                        startTime.plusMillis(20),
                        new byte[640],
                        AudioFormat.PCM_16KHZ_MONO));
        session.acceptChunk(
                new AudioChunk(
                        sessionId,
                        1L,
                        startTime.plusMillis(40),
                        new byte[640],
                        AudioFormat.PCM_16KHZ_MONO));
        session.acceptChunk(
                new AudioChunk(
                        sessionId,
                        2L,
                        startTime.plusMillis(60),
                        new byte[640],
                        AudioFormat.PCM_16KHZ_MONO));

        assertThat(session.lastSequenceNumber()).isEqualTo(2L);
        assertThat(session.state()).isInstanceOf(CallSessionState.Streaming.class);

        CallSessionState.Streaming streaming = (CallSessionState.Streaming) session.state();
        assertThat(streaming.framesReceived()).isEqualTo(3L);
    }

    @Test
    @DisplayName("rejects out-of-order or duplicate sequence numbers")
    void outOfOrderChunkThrows() {
        CallSession session = CallSession.start(sessionId, startTime);
        session.acceptChunk(
                new AudioChunk(
                        sessionId,
                        5L,
                        startTime.plusMillis(20),
                        new byte[640],
                        AudioFormat.PCM_16KHZ_MONO));

        // Duplicate sequence number 5L
        AudioChunk duplicateChunk =
                new AudioChunk(
                        sessionId,
                        5L,
                        startTime.plusMillis(40),
                        new byte[640],
                        AudioFormat.PCM_16KHZ_MONO);
        assertThatThrownBy(() -> session.acceptChunk(duplicateChunk))
                .isInstanceOf(InvalidAudioFrameException.class)
                .hasMessageContaining("Out-of-order sequence number");

        // Regressive sequence number 4L
        AudioChunk regressiveChunk =
                new AudioChunk(
                        sessionId,
                        4L,
                        startTime.plusMillis(60),
                        new byte[640],
                        AudioFormat.PCM_16KHZ_MONO);
        assertThatThrownBy(() -> session.acceptChunk(regressiveChunk))
                .isInstanceOf(InvalidAudioFrameException.class)
                .hasMessageContaining("Out-of-order sequence number");
    }

    @Test
    @DisplayName("rejects chunk with mismatched sessionId")
    void mismatchedSessionIdThrows() {
        CallSession session = CallSession.start(sessionId, startTime);
        CallSessionId otherId = CallSessionId.generate();
        AudioChunk mismatchedChunk =
                new AudioChunk(
                        otherId,
                        0L,
                        startTime.plusMillis(20),
                        new byte[640],
                        AudioFormat.PCM_16KHZ_MONO);

        assertThatThrownBy(() -> session.acceptChunk(mismatchedChunk))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Session ID mismatch");
    }

    @Test
    @DisplayName("terminate transitions session to Disconnected terminal state")
    void terminateSessionTransitionsToDisconnected() {
        CallSession session = CallSession.start(sessionId, startTime);
        Instant disconnectTime = startTime.plusSeconds(10);

        session.terminate(DisconnectReason.CLIENT_HANGUP, disconnectTime);

        assertThat(session.lastActivityAt()).isEqualTo(disconnectTime);
        assertThat(session.state()).isInstanceOf(CallSessionState.Disconnected.class);

        CallSessionState.Disconnected disconnected =
                (CallSessionState.Disconnected) session.state();
        assertThat(disconnected.disconnectedAt()).isEqualTo(disconnectTime);
        assertThat(disconnected.reason()).isEqualTo(DisconnectReason.CLIENT_HANGUP);
    }

    @Test
    @DisplayName("accepting chunk on disconnected session throws IllegalSessionStateException")
    void chunkOnDisconnectedThrows() {
        CallSession session = CallSession.start(sessionId, startTime);
        session.terminate(DisconnectReason.CLIENT_HANGUP, startTime.plusSeconds(5));

        AudioChunk chunkAfterDisconnect =
                new AudioChunk(
                        sessionId,
                        0L,
                        startTime.plusSeconds(6),
                        new byte[640],
                        AudioFormat.PCM_16KHZ_MONO);

        assertThatThrownBy(() -> session.acceptChunk(chunkAfterDisconnect))
                .isInstanceOf(IllegalSessionStateException.class)
                .hasMessageContaining("Cannot process audio chunk on terminated session");
    }

    @Test
    @DisplayName("terminate on already disconnected session is idempotent")
    void terminateIdempotent() {
        CallSession session = CallSession.start(sessionId, startTime);
        session.terminate(DisconnectReason.CLIENT_HANGUP, startTime.plusSeconds(5));

        // Re-terminating should not throw
        session.terminate(DisconnectReason.SERVER_SHUTDOWN, startTime.plusSeconds(6));
        CallSessionState.Disconnected disconnected =
                (CallSessionState.Disconnected) session.state();
        assertThat(disconnected.reason()).isEqualTo(DisconnectReason.CLIENT_HANGUP);
    }
}
