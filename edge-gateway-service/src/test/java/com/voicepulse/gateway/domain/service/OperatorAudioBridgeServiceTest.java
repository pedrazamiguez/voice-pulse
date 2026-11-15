package com.voicepulse.gateway.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voicepulse.gateway.domain.exception.CallSessionNotFoundException;
import com.voicepulse.gateway.domain.exception.IllegalSessionStateException;
import com.voicepulse.gateway.domain.exception.InvalidAudioFrameException;
import com.voicepulse.gateway.domain.model.BridgeAuthorizationStatus;
import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.gateway.domain.model.CallSessionState;
import com.voicepulse.gateway.domain.model.DisconnectReason;
import com.voicepulse.gateway.domain.ports.outbound.AudioPlaybackControlPort;
import com.voicepulse.gateway.domain.ports.outbound.OperatorAudioBridgePort;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import java.util.Optional;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("OperatorAudioBridgeService Domain Service Tests")
class OperatorAudioBridgeServiceTest {

    @Mock private SessionRegistryPort sessionRegistry;

    @Mock private AudioPlaybackControlPort audioPlaybackControlPort;

    @Mock private OperatorAudioBridgePort operatorAudioBridgePort;

    @InjectMocks private OperatorAudioBridgeService service;

    @Test
    @DisplayName(
            "activateBridge transitions session to Bridged, flushes AI playback, and pairs bridge")
    void shouldActivateBridgeAndFlushAiPlayback() {
        CallSessionId sessionId = CallSessionId.generate();
        Instant createdAt = Instant.now().minusSeconds(10);
        Instant bridgedAt = Instant.now();
        CallSession session = CallSession.start(sessionId, createdAt);
        byte[] payload = Instancio.create(byte[].class);
        byte[] validPcm = payload.length >= 2 && payload.length % 2 == 0 ? payload : new byte[640];
        session.acceptChunk(
                new AudioChunk(
                        sessionId,
                        0L,
                        createdAt.plusMillis(20),
                        validPcm,
                        AudioFormat.PCM_16KHZ_MONO));

        when(sessionRegistry.findById(sessionId)).thenReturn(Optional.of(session));

        String operatorId = "op-101";
        String endpoint = "/ws/operators/op-101/calls/" + sessionId + "/audio";
        service.activateBridge(sessionId, operatorId, endpoint, bridgedAt);

        assertThat(session.isBridged()).isTrue();
        assertThat(session.bridgedOperatorId()).contains(operatorId);
        assertThat(session.state())
                .isEqualTo(new CallSessionState.Bridged(operatorId, endpoint, bridgedAt));
        verify(sessionRegistry).save(session);
        verify(audioPlaybackControlPort).interruptPlayback(sessionId);
        verify(operatorAudioBridgePort).activateBridge(sessionId, operatorId);
    }

    @Test
    @DisplayName("activateBridge throws CallSessionNotFoundException when session does not exist")
    void shouldThrowWhenActivatingBridgeForMissingSession() {
        CallSessionId sessionId = CallSessionId.generate();
        when(sessionRegistry.findById(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.activateBridge(
                                        sessionId,
                                        "op-1",
                                        "/ws/operators/op-1/calls/" + sessionId + "/audio",
                                        Instant.now()))
                .isInstanceOf(CallSessionNotFoundException.class);
        verifyNoInteractions(audioPlaybackControlPort, operatorAudioBridgePort);
    }

    @Test
    @DisplayName(
            "authorizeOperatorBridge returns Authorized when session is bridged to same operator")
    void shouldReturnAuthorizedForMatchingOperator() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now().minusSeconds(5));
        session.rerouteToOperator(
                "op-22", "/ws/operators/op-22/calls/" + sessionId + "/audio", Instant.now());
        when(sessionRegistry.findById(sessionId)).thenReturn(Optional.of(session));

        BridgeAuthorizationStatus status = service.authorizeOperatorBridge(sessionId, "op-22");

        assertThat(status).isEqualTo(new BridgeAuthorizationStatus.Authorized(sessionId, "op-22"));
    }

    @Test
    @DisplayName(
            "authorizeOperatorBridge returns PendingReroute while session is Connected or"
                    + " Streaming")
    void shouldReturnPendingRerouteBeforeRerouteSignal() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now());
        when(sessionRegistry.findById(sessionId)).thenReturn(Optional.of(session));

        assertThat(service.authorizeOperatorBridge(sessionId, "op-22"))
                .isEqualTo(new BridgeAuthorizationStatus.PendingReroute(sessionId));
    }

    @Test
    @DisplayName(
            "authorizeOperatorBridge returns UnauthorizedOperator when bridged to another operator")
    void shouldReturnUnauthorizedOperatorWhenAssignedToDifferentOperator() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now().minusSeconds(5));
        session.rerouteToOperator(
                "op-owner", "/ws/operators/op-owner/calls/" + sessionId + "/audio", Instant.now());
        when(sessionRegistry.findById(sessionId)).thenReturn(Optional.of(session));

        BridgeAuthorizationStatus status =
                service.authorizeOperatorBridge(sessionId, "op-intruder");

        assertThat(status)
                .isEqualTo(
                        new BridgeAuthorizationStatus.UnauthorizedOperator(
                                sessionId, "op-intruder", "op-owner"));
    }

    @Test
    @DisplayName("authorizeOperatorBridge returns SessionUnavailable when missing or terminated")
    void shouldReturnSessionUnavailableWhenMissingOrDisconnected() {
        CallSessionId missingId = CallSessionId.generate();
        when(sessionRegistry.findById(missingId)).thenReturn(Optional.empty());
        assertThat(service.authorizeOperatorBridge(missingId, "op-1"))
                .isEqualTo(new BridgeAuthorizationStatus.SessionUnavailable(missingId));

        CallSessionId closedId = CallSessionId.generate();
        CallSession closed = CallSession.start(closedId, Instant.now().minusSeconds(10));
        closed.terminate(DisconnectReason.CLIENT_HANGUP, Instant.now());
        when(sessionRegistry.findById(closedId)).thenReturn(Optional.of(closed));
        assertThat(service.authorizeOperatorBridge(closedId, "op-1"))
                .isEqualTo(new BridgeAuthorizationStatus.SessionUnavailable(closedId));
    }

    @Test
    @DisplayName(
            "relayOperatorAudio validates PCM payload and forwards when session is bridged to"
                    + " operator")
    void shouldRelayOperatorAudioWhenBridgedAndAuthorized() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now().minusSeconds(5));
        session.rerouteToOperator(
                "op-5", "/ws/operators/op-5/calls/" + sessionId + "/audio", Instant.now());
        when(sessionRegistry.findById(sessionId)).thenReturn(Optional.of(session));

        byte[] pcmData = new byte[640];
        service.relayOperatorAudio(sessionId, "op-5", pcmData);

        verify(operatorAudioBridgePort).relayOperatorAudio(sessionId, "op-5", pcmData);
    }

    @Test
    @DisplayName(
            "relayOperatorAudio rejects unauthorized operator, unbridged session, or invalid PCM")
    void shouldRejectInvalidRelayOperatorAudio() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession streaming = CallSession.start(sessionId, Instant.now().minusSeconds(5));
        when(sessionRegistry.findById(sessionId)).thenReturn(Optional.of(streaming));

        assertThatThrownBy(() -> service.relayOperatorAudio(sessionId, "op-5", new byte[640]))
                .isInstanceOf(IllegalSessionStateException.class);

        streaming.rerouteToOperator(
                "op-owner", "/ws/operators/op-owner/calls/" + sessionId + "/audio", Instant.now());
        assertThatThrownBy(() -> service.relayOperatorAudio(sessionId, "op-other", new byte[640]))
                .isInstanceOf(IllegalSessionStateException.class);

        assertThatThrownBy(() -> service.relayOperatorAudio(sessionId, "op-owner", new byte[3]))
                .isInstanceOf(InvalidAudioFrameException.class);
    }

    @Test
    @DisplayName("handleOperatorDisconnected delegates to operatorAudioBridgePort detachOperator")
    void shouldDetachOperatorOnDisconnect() {
        CallSessionId sessionId = CallSessionId.generate();

        service.handleOperatorDisconnected(sessionId, "op-9");

        verify(operatorAudioBridgePort).detachOperator(sessionId, "op-9");
    }
}
