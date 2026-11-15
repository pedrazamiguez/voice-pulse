package com.voicepulse.gateway.domain.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.gateway.domain.model.DisconnectReason;
import com.voicepulse.gateway.domain.ports.outbound.AudioFramePublisherPort;
import com.voicepulse.gateway.domain.ports.outbound.AudioPlaybackControlPort;
import com.voicepulse.gateway.domain.ports.outbound.OperatorAudioBridgePort;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.AudioFormat;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CallSessionService Bridge Routing & Teardown Tests")
class CallSessionServiceBridgeTest {

    @Mock private SessionRegistryPort sessionRegistry;

    @Mock private AudioFramePublisherPort audioFramePublisher;

    @Mock private AudioPlaybackControlPort audioPlaybackControlPort;

    @Mock private OperatorAudioBridgePort operatorAudioBridgePort;

    @InjectMocks private CallSessionService service;

    @Test
    @DisplayName(
            "ingestAudioChunk routes Bridged caller frames to OperatorAudioBridgePort instead of"
                    + " Kafka")
    void shouldRouteBridgedCallerAudioDirectlyToOperatorBridge() {
        CallSessionId sessionId = CallSessionId.generate();
        Instant t0 = Instant.now().minusSeconds(5);
        CallSession session = CallSession.start(sessionId, t0);
        session.rerouteToOperator(
                "op-99", "/ws/operators/op-99/calls/" + sessionId + "/audio", t0.plusSeconds(1));
        when(sessionRegistry.findById(sessionId)).thenReturn(Optional.of(session));

        AudioChunk chunk =
                new AudioChunk(
                        sessionId, 0L, Instant.now(), new byte[640], AudioFormat.PCM_16KHZ_MONO);
        service.ingestAudioChunk(chunk);

        verify(operatorAudioBridgePort).relayCallerAudio(chunk);
        verifyNoInteractions(audioFramePublisher);
        verify(sessionRegistry).save(session);
    }

    @Test
    @DisplayName("terminateSession closes any active operator bridge")
    void shouldCloseOperatorBridgeWhenCallTerminates() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now().minusSeconds(5));
        when(sessionRegistry.findById(sessionId)).thenReturn(Optional.of(session));

        service.terminateSession(sessionId, DisconnectReason.CLIENT_HANGUP);

        verify(sessionRegistry).save(session);
        verify(operatorAudioBridgePort).closeBridge(sessionId);
    }
}
