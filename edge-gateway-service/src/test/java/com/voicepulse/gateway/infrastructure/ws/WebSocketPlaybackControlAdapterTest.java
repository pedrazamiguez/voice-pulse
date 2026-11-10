package com.voicepulse.gateway.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.gateway.infrastructure.registry.ConcurrentSessionRegistry;
import com.voicepulse.shared.model.CallSessionId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketPlaybackControlAdapter Unit Tests")
class WebSocketPlaybackControlAdapterTest {

    @Mock private ConcurrentSessionRegistry sessionRegistry;

    @Mock private WebSocketSession webSocketSession;

    @Mock private WebSocketMessage textMessage;

    @InjectMocks private WebSocketPlaybackControlAdapter adapter;

    @Captor private ArgumentCaptor<String> payloadCaptor;

    @Test
    @DisplayName("interruptPlayback purges outbound buffer and emits OP_INTERRUPT text message")
    void interruptPlaybackPurgesAndEmitsInterrupt() {
        CallSessionId sessionId = CallSessionId.generate();
        when(sessionRegistry.purgeOutboundBuffer(sessionId)).thenReturn(3);
        when(sessionRegistry.getSession(sessionId)).thenReturn(Optional.of(webSocketSession));
        when(webSocketSession.isOpen()).thenReturn(true);
        when(webSocketSession.textMessage(payloadCaptor.capture())).thenReturn(textMessage);
        when(sessionRegistry.emitMessage(sessionId, textMessage)).thenReturn(true);

        adapter.interruptPlayback(sessionId);

        verify(sessionRegistry).purgeOutboundBuffer(sessionId);
        verify(webSocketSession).textMessage(any());
        verify(sessionRegistry).emitMessage(eq(sessionId), eq(textMessage));

        assertThat(payloadCaptor.getValue())
                .contains("\"op\":\"INTERRUPT\"")
                .contains("\"callId\":\"" + sessionId.value() + "\"");
    }

    @Test
    @DisplayName(
            "interruptPlayback purges buffer and exits gracefully when WebSocket session is absent")
    void interruptPlaybackWhenSessionAbsent() {
        CallSessionId sessionId = CallSessionId.generate();
        when(sessionRegistry.purgeOutboundBuffer(sessionId)).thenReturn(0);
        when(sessionRegistry.getSession(sessionId)).thenReturn(Optional.empty());

        adapter.interruptPlayback(sessionId);

        verify(sessionRegistry).purgeOutboundBuffer(sessionId);
        verify(sessionRegistry, never()).emitMessage(any(), any());
    }

    @Test
    @DisplayName(
            "interruptPlayback purges buffer and exits gracefully when WebSocket session is closed")
    void interruptPlaybackWhenSessionClosed() {
        CallSessionId sessionId = CallSessionId.generate();
        when(sessionRegistry.purgeOutboundBuffer(sessionId)).thenReturn(1);
        when(sessionRegistry.getSession(sessionId)).thenReturn(Optional.of(webSocketSession));
        when(webSocketSession.isOpen()).thenReturn(false);

        adapter.interruptPlayback(sessionId);

        verify(sessionRegistry).purgeOutboundBuffer(sessionId);
        verify(sessionRegistry, never()).emitMessage(any(), any());
    }

    @Test
    @DisplayName("interruptPlayback rejects null sessionId")
    void interruptPlaybackRejectsNullSessionId() {
        assertThatThrownBy(() -> adapter.interruptPlayback(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId cannot be null");
    }
}
