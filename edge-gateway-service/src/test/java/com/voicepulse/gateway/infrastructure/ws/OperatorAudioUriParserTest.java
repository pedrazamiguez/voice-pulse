package com.voicepulse.gateway.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voicepulse.shared.model.CallSessionId;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@DisplayName("OperatorAudioUriParser Unit Tests")
class OperatorAudioUriParserTest {

    private final OperatorAudioUriParser parser = new OperatorAudioUriParser();

    @Test
    @DisplayName("parses valid operatorId and UUID callId from URI template attributes")
    void parsesFromHandshakeAttributes() {
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo info =
                new HandshakeInfo(
                        URI.create(
                                "ws://localhost:8080/ws/operators/op-12/calls/"
                                        + sessionId
                                        + "/audio"),
                        new HttpHeaders(),
                        Mono.empty(),
                        null,
                        null,
                        Map.of(
                                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                                Map.of("operatorId", "op-12", "callId", sessionId.toString())),
                        null);
        when(session.getHandshakeInfo()).thenReturn(info);

        Optional<OperatorAudioTarget> target = parser.parse(session);

        assertThat(target).contains(new OperatorAudioTarget("op-12", sessionId));
    }

    @Test
    @DisplayName("falls back to parsing URI path when template attributes are absent")
    void parsesFromUriPathFallback() {
        CallSessionId sessionId = CallSessionId.generate();
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo info =
                new HandshakeInfo(
                        URI.create(
                                "ws://localhost:8080/ws/operators/agent_07/calls/"
                                        + sessionId
                                        + "/audio"),
                        new HttpHeaders(),
                        Mono.empty(),
                        null,
                        null,
                        Map.of(),
                        null);
        when(session.getHandshakeInfo()).thenReturn(info);

        Optional<OperatorAudioTarget> target = parser.parse(session);

        assertThat(target).contains(new OperatorAudioTarget("agent_07", sessionId));
    }

    @Test
    @DisplayName(
            "rejects malformed paths, non-UUID callIds, oversized operatorIds, and template"
                    + " literals")
    void rejectsMalformedPaths() {
        CallSessionId sessionId = CallSessionId.generate();
        assertThat(parser.parsePath(null)).isEmpty();
        assertThat(parser.parsePath("/ws/calls/" + sessionId + "/audio")).isEmpty();
        assertThat(parser.parsePath("/ws/operators/op-1/calls/not-a-uuid/audio")).isEmpty();
        assertThat(parser.parsePath("/ws/operators/{operatorId}/calls/" + sessionId + "/audio"))
                .isEmpty();
        assertThat(parser.parsePath("/ws/operators/op 1/calls/" + sessionId + "/audio")).isEmpty();
        assertThat(parser.parsePath("/ws/operators/op%201/calls/" + sessionId + "/audio"))
                .isEmpty();
        assertThat(
                        parser.parsePath(
                                "/ws/operators/"
                                        + "x".repeat(65)
                                        + "/calls/"
                                        + sessionId
                                        + "/audio"))
                .isEmpty();
    }
}
