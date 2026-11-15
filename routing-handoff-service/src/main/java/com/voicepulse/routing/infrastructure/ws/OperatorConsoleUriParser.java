package com.voicepulse.routing.infrastructure.ws;

import com.voicepulse.routing.domain.model.OperatorId;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * Parses and validates {@link OperatorId} path parameters from Operator Web Console WebSocket
 * handshake URIs ({@code /ws/operators/{operatorId}}).
 */
@Component
public class OperatorConsoleUriParser {

    private static final Pattern PATH_PATTERN = Pattern.compile("^/ws/operators/([^/]+)/?$");

    /** Sealed outcome representing the result of parsing an operator WebSocket URI. */
    public sealed interface ParseResult
            permits ParseResult.Success, ParseResult.InvalidOperatorId, ParseResult.InvalidPath {

        /** Successfully extracted and validated {@link OperatorId}. */
        record Success(OperatorId operatorId) implements ParseResult {}

        /** URI path matched, but the operatorId segment failed domain validation. */
        record InvalidOperatorId(String rawOperatorId, String reason) implements ParseResult {}

        /** Handshake URI path did not match {@code /ws/operators/{operatorId}}. */
        record InvalidPath(String path) implements ParseResult {}
    }

    /** Parses the {@link WebSocketSession} URI and attributes to resolve {@link OperatorId}. */
    public ParseResult parse(WebSocketSession session) {
        if (session == null) {
            return new ParseResult.InvalidPath(null);
        }

        String rawOperatorId = extractRawOperatorId(session);
        if (rawOperatorId == null) {
            return new ParseResult.InvalidPath(extractPath(session));
        }

        try {
            return new ParseResult.Success(OperatorId.fromString(rawOperatorId));
        } catch (IllegalArgumentException ex) {
            return new ParseResult.InvalidOperatorId(rawOperatorId, ex.getMessage());
        }
    }

    /** Extracts raw operatorId from a path string matching {@code /ws/operators/{operatorId}}. */
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

    /** Extracts operatorId from WebFlux URI template variables attribute map. */
    public Optional<String> extractFromAttributes(Map<String, Object> attributes) {
        if (attributes == null) {
            return Optional.empty();
        }
        Object uriVars = attributes.get(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (uriVars instanceof Map<?, ?> map && map.containsKey("operatorId")) {
            Object value = map.get("operatorId");
            return value == null ? Optional.empty() : Optional.of(value.toString());
        }
        return Optional.empty();
    }

    /** Returns the URI path of the session handshake if present. */
    public String extractPath(WebSocketSession session) {
        if (session != null && session.getHandshakeInfo() != null) {
            URI uri = session.getHandshakeInfo().getUri();
            if (uri != null) {
                return uri.getPath();
            }
        }
        return null;
    }

    private String extractRawOperatorId(WebSocketSession session) {
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
