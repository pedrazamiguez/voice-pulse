package com.voicepulse.gateway.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.voicepulse.shared.model.CallSessionId;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;

@DisplayName("WebSocketCallUriParser Path & Attribute Extraction Tests")
class WebSocketCallUriParserTest {

    private WebSocketCallUriParser parser;

    @BeforeEach
    void setUp() {
        parser = new WebSocketCallUriParser();
    }

    private WebSocketSession createSession(
            String path, Map<String, Object> sessionAttrs, Map<String, Object> handshakeAttrs) {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);

        when(session.getAttributes())
                .thenReturn(sessionAttrs != null ? sessionAttrs : Collections.emptyMap());
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getAttributes())
                .thenReturn(handshakeAttrs != null ? handshakeAttrs : Collections.emptyMap());
        if (path != null) {
            when(handshakeInfo.getUri()).thenReturn(URI.create("ws://localhost:8080" + path));
        }

        return session;
    }

    @Test
    @DisplayName("Valid URI path parses successfully into CallSessionId")
    void parse_validPath_returnsSuccess() {
        CallSessionId expectedId = CallSessionId.generate();
        WebSocketSession session = createSession("/ws/calls/" + expectedId + "/audio", null, null);

        WebSocketCallUriParser.ParseResult result = parser.parse(session);

        assertThat(result).isInstanceOf(WebSocketCallUriParser.ParseResult.Success.class);
        WebSocketCallUriParser.ParseResult.Success success =
                (WebSocketCallUriParser.ParseResult.Success) result;
        assertThat(success.sessionId()).isEqualTo(expectedId);
    }

    @Test
    @DisplayName("Valid URI path with trailing slash parses successfully")
    void parse_validPathWithTrailingSlash_returnsSuccess() {
        CallSessionId expectedId = CallSessionId.generate();
        WebSocketSession session = createSession("/ws/calls/" + expectedId + "/audio/", null, null);

        WebSocketCallUriParser.ParseResult result = parser.parse(session);

        assertThat(result).isInstanceOf(WebSocketCallUriParser.ParseResult.Success.class);
        WebSocketCallUriParser.ParseResult.Success success =
                (WebSocketCallUriParser.ParseResult.Success) result;
        assertThat(success.sessionId()).isEqualTo(expectedId);
    }

    @Test
    @DisplayName("Session attributes take priority and parse successfully")
    void parse_sessionAttributes_returnsSuccess() {
        CallSessionId expectedId = CallSessionId.generate();
        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("callId", expectedId.toString()));

        WebSocketSession session = createSession("/ws/other/path", sessionAttrs, null);

        WebSocketCallUriParser.ParseResult result = parser.parse(session);

        assertThat(result).isInstanceOf(WebSocketCallUriParser.ParseResult.Success.class);
        WebSocketCallUriParser.ParseResult.Success success =
                (WebSocketCallUriParser.ParseResult.Success) result;
        assertThat(success.sessionId()).isEqualTo(expectedId);
    }

    @Test
    @DisplayName("Handshake attributes parse successfully when session attributes are absent")
    void parse_handshakeAttributes_returnsSuccess() {
        CallSessionId expectedId = CallSessionId.generate();
        Map<String, Object> handshakeAttrs = new HashMap<>();
        handshakeAttrs.put(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("callId", expectedId.toString()));

        WebSocketSession session = createSession("/ws/other/path", null, handshakeAttrs);

        WebSocketCallUriParser.ParseResult result = parser.parse(session);

        assertThat(result).isInstanceOf(WebSocketCallUriParser.ParseResult.Success.class);
        WebSocketCallUriParser.ParseResult.Success success =
                (WebSocketCallUriParser.ParseResult.Success) result;
        assertThat(success.sessionId()).isEqualTo(expectedId);
    }

    @Test
    @DisplayName("Unmatched URI path returns InvalidPath with extracted path string")
    void parse_unmatchedPath_returnsInvalidPath() {
        WebSocketSession session = createSession("/ws/unknown/endpoint", null, null);

        WebSocketCallUriParser.ParseResult result = parser.parse(session);

        assertThat(result).isInstanceOf(WebSocketCallUriParser.ParseResult.InvalidPath.class);
        WebSocketCallUriParser.ParseResult.InvalidPath invalidPath =
                (WebSocketCallUriParser.ParseResult.InvalidPath) result;
        assertThat(invalidPath.path()).isEqualTo("/ws/unknown/endpoint");
    }

    @Test
    @DisplayName("Malformed UUID in URI path returns InvalidCallId with raw value")
    void parse_malformedUuidInPath_returnsInvalidCallId() {
        WebSocketSession session = createSession("/ws/calls/not-a-valid-uuid/audio", null, null);

        WebSocketCallUriParser.ParseResult result = parser.parse(session);

        assertThat(result).isInstanceOf(WebSocketCallUriParser.ParseResult.InvalidCallId.class);
        WebSocketCallUriParser.ParseResult.InvalidCallId invalidCallId =
                (WebSocketCallUriParser.ParseResult.InvalidCallId) result;
        assertThat(invalidCallId.rawCallId()).isEqualTo("not-a-valid-uuid");
    }

    @Test
    @DisplayName("Malformed UUID in attributes returns InvalidCallId with raw value")
    void parse_malformedUuidInAttributes_returnsInvalidCallId() {
        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("callId", "12345-bad"));

        WebSocketSession session = createSession("/ws/calls/placeholder/audio", sessionAttrs, null);

        WebSocketCallUriParser.ParseResult result = parser.parse(session);

        assertThat(result).isInstanceOf(WebSocketCallUriParser.ParseResult.InvalidCallId.class);
        WebSocketCallUriParser.ParseResult.InvalidCallId invalidCallId =
                (WebSocketCallUriParser.ParseResult.InvalidCallId) result;
        assertThat(invalidCallId.rawCallId()).isEqualTo("12345-bad");
    }

    @Test
    @DisplayName("Null session returns InvalidPath(null)")
    void parse_nullSession_returnsInvalidPath() {
        WebSocketCallUriParser.ParseResult result = parser.parse(null);

        assertThat(result).isInstanceOf(WebSocketCallUriParser.ParseResult.InvalidPath.class);
        WebSocketCallUriParser.ParseResult.InvalidPath invalidPath =
                (WebSocketCallUriParser.ParseResult.InvalidPath) result;
        assertThat(invalidPath.path()).isNull();
    }

    @Test
    @DisplayName("Null HandshakeInfo returns InvalidPath(null)")
    void parse_nullHandshakeInfo_returnsInvalidPath() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Collections.emptyMap());
        when(session.getHandshakeInfo()).thenReturn(null);

        WebSocketCallUriParser.ParseResult result = parser.parse(session);

        assertThat(result).isInstanceOf(WebSocketCallUriParser.ParseResult.InvalidPath.class);
        WebSocketCallUriParser.ParseResult.InvalidPath invalidPath =
                (WebSocketCallUriParser.ParseResult.InvalidPath) result;
        assertThat(invalidPath.path()).isNull();
    }

    @Test
    @DisplayName("Null URI in HandshakeInfo returns InvalidPath(null)")
    void parse_nullUriInHandshake_returnsInvalidPath() {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getAttributes()).thenReturn(Collections.emptyMap());
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getAttributes()).thenReturn(Collections.emptyMap());
        when(handshakeInfo.getUri()).thenReturn(null);

        WebSocketCallUriParser.ParseResult result = parser.parse(session);

        assertThat(result).isInstanceOf(WebSocketCallUriParser.ParseResult.InvalidPath.class);
        WebSocketCallUriParser.ParseResult.InvalidPath invalidPath =
                (WebSocketCallUriParser.ParseResult.InvalidPath) result;
        assertThat(invalidPath.path()).isNull();
    }

    @Test
    @DisplayName("extractCallId returns raw String when valid")
    void extractCallId_returnsRawString() {
        CallSessionId expectedId = CallSessionId.generate();
        WebSocketSession session = createSession("/ws/calls/" + expectedId + "/audio", null, null);

        Optional<String> extracted = parser.extractCallId(session);

        assertThat(extracted).isPresent().contains(expectedId.toString());
    }

    @Test
    @DisplayName("extractFromPath handles null, non-matching, and matching paths")
    void extractFromPath_handlesBoundaryCases() {
        assertThat(parser.extractFromPath(null)).isEmpty();
        assertThat(parser.extractFromPath("/ws/unknown")).isEmpty();
        assertThat(parser.extractFromPath("/ws/calls//audio")).isEmpty();
        assertThat(parser.extractFromPath("/ws/calls/abc-123/audio")).contains("abc-123");
        assertThat(parser.extractFromPath("/ws/calls/abc-123/audio/")).contains("abc-123");
    }

    @Test
    @DisplayName("extractFromAttributes handles null, empty, and valid attribute maps")
    void extractFromAttributes_handlesBoundaryCases() {
        assertThat(parser.extractFromAttributes(null)).isEmpty();
        assertThat(parser.extractFromAttributes(Collections.emptyMap())).isEmpty();

        Map<String, Object> mapWithoutVars = Map.of("other", "value");
        assertThat(parser.extractFromAttributes(mapWithoutVars)).isEmpty();

        Map<String, Object> mapWithNonMapVars =
                Map.of(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, "not-a-map");
        assertThat(parser.extractFromAttributes(mapWithNonMapVars)).isEmpty();

        Map<String, Object> validMap = new HashMap<>();
        Map<String, Object> vars = new HashMap<>();
        vars.put("callId", "session-xyz");
        validMap.put(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, vars);
        assertThat(parser.extractFromAttributes(validMap)).contains("session-xyz");

        // Map with null callId value
        vars.put("callId", null);
        assertThat(parser.extractFromAttributes(validMap)).isEmpty();
    }

    @Test
    @DisplayName("extractPath returns URI path or null safely")
    void extractPath_returnsPathSafely() {
        assertThat(parser.extractPath(null)).isNull();

        WebSocketSession sessionWithoutHandshake = mock(WebSocketSession.class);
        when(sessionWithoutHandshake.getHandshakeInfo()).thenReturn(null);
        assertThat(parser.extractPath(sessionWithoutHandshake)).isNull();

        WebSocketSession validSession = createSession("/ws/test/path", null, null);
        assertThat(parser.extractPath(validSession)).isEqualTo("/ws/test/path");
    }
}
