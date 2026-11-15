package com.voicepulse.routing.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.voicepulse.routing.domain.model.OperatorId;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;

@DisplayName("OperatorConsoleUriParser Tests")
class OperatorConsoleUriParserTest {

    private final OperatorConsoleUriParser parser = new OperatorConsoleUriParser();

    private static WebSocketSession mockSession(
            String path, Map<String, Object> sessionAttrs, Map<String, Object> handshakeAttrs) {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        lenient().when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        lenient()
                .when(handshakeInfo.getUri())
                .thenReturn(path == null ? null : URI.create("ws://localhost:8082" + path));
        lenient().when(session.getAttributes()).thenReturn(sessionAttrs);
        lenient().when(handshakeInfo.getAttributes()).thenReturn(handshakeAttrs);
        return session;
    }

    @Test
    @DisplayName("Extracts valid OperatorId from path URI and URI template attributes")
    void parsesValidOperatorId() {
        WebSocketSession fromPath =
                mockSession(
                        "/ws/operators/op-alex-1", Collections.emptyMap(), Collections.emptyMap());
        OperatorConsoleUriParser.ParseResult pathResult = parser.parse(fromPath);
        assertThat(pathResult).isInstanceOf(OperatorConsoleUriParser.ParseResult.Success.class);
        assertThat(((OperatorConsoleUriParser.ParseResult.Success) pathResult).operatorId())
                .isEqualTo(OperatorId.fromString("op-alex-1"));

        WebSocketSession fromAttrs =
                mockSession(
                        "/ws/operators/ignored",
                        Map.of(
                                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                                Map.of("operatorId", "op-gold-9")),
                        Collections.emptyMap());
        OperatorConsoleUriParser.ParseResult attrResult = parser.parse(fromAttrs);
        assertThat(attrResult).isInstanceOf(OperatorConsoleUriParser.ParseResult.Success.class);
        assertThat(((OperatorConsoleUriParser.ParseResult.Success) attrResult).operatorId())
                .isEqualTo(OperatorId.fromString("op-gold-9"));
    }

    @Test
    @DisplayName("Rejects invalid operatorId and unmatched paths")
    void rejectsInvalidOperatorIdAndPaths() {
        String tooLong = "x".repeat(65);
        WebSocketSession invalidOp =
                mockSession(
                        "/ws/operators/" + tooLong, Collections.emptyMap(), Collections.emptyMap());
        assertThat(parser.parse(invalidOp))
                .isInstanceOf(OperatorConsoleUriParser.ParseResult.InvalidOperatorId.class);

        WebSocketSession invalidPath =
                mockSession("/ws/calls/123/audio", Collections.emptyMap(), Collections.emptyMap());
        assertThat(parser.parse(invalidPath))
                .isInstanceOf(OperatorConsoleUriParser.ParseResult.InvalidPath.class);

        assertThat(parser.parse(null))
                .isInstanceOf(OperatorConsoleUriParser.ParseResult.InvalidPath.class);
    }
}
