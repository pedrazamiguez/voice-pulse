package com.voicepulse.gateway.infrastructure.ws;

import com.voicepulse.shared.model.CallSessionId;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * Parses and validates WebSocket handshake URIs and routing attributes for incoming telephony audio
 * streaming sessions.
 */
@Slf4j
@Component
public class WebSocketCallUriParser {

    private static final Pattern PATH_PATTERN = Pattern.compile("^/ws/calls/([^/]+)/audio/?$");

    /** Sealed outcome representing the result of parsing a WebSocket call session URI. */
    public sealed interface ParseResult
            permits ParseResult.Success, ParseResult.InvalidCallId, ParseResult.InvalidPath {

        /** Successfully parsed valid CallSessionId. */
        record Success(CallSessionId sessionId) implements ParseResult {}

        /** Path matched, but callId value failed UUID validation. */
        record InvalidCallId(String rawCallId) implements ParseResult {}

        /** Handshake URI path did not match the expected pattern. */
        record InvalidPath(String path) implements ParseResult {}
    }

    /**
     * Parses the WebSocketSession to extract and validate the CallSessionId.
     *
     * @param session the inbound WebSocketSession
     * @return the ParseResult outcome
     */
    public ParseResult parse(WebSocketSession session) {
        if (session == null) {
            return new ParseResult.InvalidPath(null);
        }

        String rawCallId = extractRawCallId(session);
        if (rawCallId == null) {
            return new ParseResult.InvalidPath(extractPath(session));
        }

        try {
            CallSessionId sessionId = CallSessionId.fromString(rawCallId);
            return new ParseResult.Success(sessionId);
        } catch (IllegalArgumentException ex) {
            return new ParseResult.InvalidCallId(rawCallId);
        }
    }

    /**
     * Extracts raw callId string from session attributes, handshake attributes, or path regex.
     *
     * @param session the WebSocketSession
     * @return Optional containing the raw callId string, or empty
     */
    public Optional<String> extractCallId(WebSocketSession session) {
        return Optional.ofNullable(extractRawCallId(session));
    }

    /**
     * Extracts callId from a raw URI path string.
     *
     * @param path the URI path
     * @return Optional containing the extracted callId, or empty
     */
    public Optional<String> extractFromPath(String path) {
        if (path == null) {
            return Optional.empty();
        }
        Matcher matcher = PATH_PATTERN.matcher(path);
        if (matcher.matches()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    /**
     * Extracts callId from URI template variables attribute map.
     *
     * @param attributes attribute map
     * @return Optional containing the extracted callId, or empty
     */
    public Optional<String> extractFromAttributes(Map<String, Object> attributes) {
        if (attributes == null) {
            return Optional.empty();
        }
        Object uriVars = attributes.get(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (uriVars instanceof Map<?, ?> map && map.containsKey("callId")) {
            Object val = map.get("callId");
            return val != null ? Optional.of(val.toString()) : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Extracts the URI path from the session's HandshakeInfo.
     *
     * @param session the WebSocketSession
     * @return path string, or null
     */
    public String extractPath(WebSocketSession session) {
        if (session != null && session.getHandshakeInfo() != null) {
            URI uri = session.getHandshakeInfo().getUri();
            if (uri != null) {
                return uri.getPath();
            }
        }
        return null;
    }

    private String extractRawCallId(WebSocketSession session) {
        if (session == null) {
            return null;
        }

        Optional<String> fromAttrs = extractFromAttributes(session.getAttributes());
        if (fromAttrs.isPresent()) {
            return fromAttrs.get();
        }

        if (session.getHandshakeInfo() != null) {
            Optional<String> fromHandshake =
                    extractFromAttributes(session.getHandshakeInfo().getAttributes());
            if (fromHandshake.isPresent()) {
                return fromHandshake.get();
            }

            URI uri = session.getHandshakeInfo().getUri();
            if (uri != null) {
                return extractFromPath(uri.getPath()).orElse(null);
            }
        }

        return null;
    }
}
