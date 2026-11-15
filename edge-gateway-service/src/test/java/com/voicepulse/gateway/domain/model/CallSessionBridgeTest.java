package com.voicepulse.gateway.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.gateway.domain.exception.IllegalSessionStateException;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CallSession Bridged State & BridgeAuthorizationStatus Tests")
class CallSessionBridgeTest {

    @Test
    @DisplayName(
            "rerouteToOperator transitions active session to Bridged and is idempotent for same"
                    + " assignment")
    void shouldTransitionToBridgedAndBeIdempotent() {
        CallSessionId sessionId = CallSessionId.generate();
        Instant createdAt = Instant.now().minusSeconds(10);
        CallSession session = CallSession.start(sessionId, createdAt);
        Instant bridgedAt = Instant.now();
        String endpoint = "/ws/operators/op-42/calls/" + sessionId + "/audio";

        session.rerouteToOperator("op-42", endpoint, bridgedAt);

        assertThat(session.isBridged()).isTrue();
        assertThat(session.bridgedOperatorId()).contains("op-42");
        assertThat(session.state())
                .isEqualTo(new CallSessionState.Bridged("op-42", endpoint, bridgedAt));

        session.rerouteToOperator("op-42", endpoint, bridgedAt.plusSeconds(1));
        assertThat(session.state())
                .isEqualTo(new CallSessionState.Bridged("op-42", endpoint, bridgedAt));
    }

    @Test
    @DisplayName("acceptChunk preserves Bridged state while updating sequence number and timestamp")
    void shouldPreserveBridgedStateOnAudioChunk() {
        CallSessionId sessionId = CallSessionId.generate();
        Instant t0 = Instant.now().minusSeconds(5);
        CallSession session = CallSession.start(sessionId, t0);
        session.acceptChunk(
                new AudioChunk(
                        sessionId,
                        0L,
                        t0.plusMillis(20),
                        new byte[640],
                        AudioFormat.PCM_16KHZ_MONO));
        Instant bridgedAt = t0.plusSeconds(1);
        String endpoint = "/ws/operators/op-7/calls/" + sessionId + "/audio";
        session.rerouteToOperator("op-7", endpoint, bridgedAt);

        Instant chunkTs = bridgedAt.plusMillis(40);
        session.acceptChunk(
                new AudioChunk(sessionId, 1L, chunkTs, new byte[640], AudioFormat.PCM_16KHZ_MONO));

        assertThat(session.isBridged()).isTrue();
        assertThat(session.state())
                .isEqualTo(new CallSessionState.Bridged("op-7", endpoint, bridgedAt));
        assertThat(session.lastSequenceNumber()).isEqualTo(1L);
        assertThat(session.lastActivityAt()).isEqualTo(chunkTs);
    }

    @Test
    @DisplayName("rerouteToOperator rejects conflicting operator assignment or terminated session")
    void shouldRejectConflictingOperatorOrTerminatedSession() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now().minusSeconds(5));
        session.rerouteToOperator(
                "op-1", "/ws/operators/op-1/calls/" + sessionId + "/audio", Instant.now());

        assertThatThrownBy(
                        () ->
                                session.rerouteToOperator(
                                        "op-2",
                                        "/ws/operators/op-2/calls/" + sessionId + "/audio",
                                        Instant.now()))
                .isInstanceOf(IllegalSessionStateException.class);

        CallSession closed =
                CallSession.start(CallSessionId.generate(), Instant.now().minusSeconds(5));
        closed.terminate(DisconnectReason.CLIENT_HANGUP, Instant.now());
        assertThatThrownBy(
                        () ->
                                closed.rerouteToOperator(
                                        "op-1",
                                        "/ws/operators/op-1/calls/" + closed.id() + "/audio",
                                        Instant.now()))
                .isInstanceOf(IllegalSessionStateException.class);
    }

    @Test
    @DisplayName("CallSessionState.Bridged and BridgeAuthorizationStatus validate non-blank fields")
    void shouldValidateValueObjects() {
        CallSessionId sessionId = CallSessionId.generate();
        assertThatThrownBy(() -> new CallSessionState.Bridged(" ", "/ws/path", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallSessionState.Bridged("op-1", " ", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BridgeAuthorizationStatus.Authorized(sessionId, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new BridgeAuthorizationStatus.UnauthorizedOperator(
                                        sessionId, " ", "op-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new BridgeAuthorizationStatus.UnauthorizedOperator(
                                        sessionId, "op-2", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
