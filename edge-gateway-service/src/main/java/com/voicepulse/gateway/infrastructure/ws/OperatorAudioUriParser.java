package com.voicepulse.gateway.infrastructure.ws;

import com.voicepulse.shared.model.CallSessionId;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriTemplate;

/**
 * Extracts and validates {@code operatorId} and {@code callId} from incoming WebSocket handshake
 * requests targeting {@code /ws/operators/{operatorId}/calls/{callId}/audio}.
 */
@Slf4j
@Component
public class OperatorAudioUriParser {

    private static final int MAX_OPERATOR_ID_LENGTH = 64;
    private static final UriTemplate BRIDGE_URI_TEMPLATE =
            new UriTemplate(WebSocketConfiguration.OPERATOR_AUDIO_PATH);

    /**
     * Parses operator and call identifiers from the session handshake attributes or URI path.
     *
     * @param session incoming WebSocket session
     * @return validated {@link OperatorAudioTarget}, or empty when path/variables are malformed
     */
    public Optional<OperatorAudioTarget> parse(WebSocketSession session) {
        if (session == null) {
            return Optional.empty();
        }

        Optional<OperatorAudioTarget> fromAttributes = extractFromAttributes(session);
        if (fromAttributes.isPresent()) {
            return fromAttributes;
        }

        return parsePath(session.getHandshakeInfo().getUri().getPath());
    }

    /**
     * Parses operator and call identifiers directly from a URI path string.
     *
     * @param path request URI path
     * @return validated {@link OperatorAudioTarget}, or empty when invalid
     */
    public Optional<OperatorAudioTarget> parsePath(String path) {
        if (path == null || path.isBlank() || !BRIDGE_URI_TEMPLATE.matches(path)) {
            return Optional.empty();
        }
        Map<String, String> variables = BRIDGE_URI_TEMPLATE.match(path);
        return buildTarget(variables.get("operatorId"), variables.get("callId"));
    }

    @SuppressWarnings("unchecked")
    private Optional<OperatorAudioTarget> extractFromAttributes(WebSocketSession session) {
        Object attr =
                session.getHandshakeInfo()
                        .getAttributes()
                        .get(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attr instanceof Map<?, ?> rawMap && rawMap.containsKey("callId")) {
            Object rawOperatorId = rawMap.get("operatorId");
            Object rawCallId = rawMap.get("callId");
            if (rawOperatorId instanceof String operatorId && rawCallId instanceof String callId) {
                return buildTarget(operatorId, callId);
            }
        }
        return Optional.empty();
    }

    private Optional<OperatorAudioTarget> buildTarget(String operatorId, String callId) {
        if (!isValidOperatorId(operatorId) || callId == null || callId.isBlank()) {
            log.warn(
                    "Invalid operator bridge handshake parameters: operatorId={}, callId={}",
                    operatorId,
                    callId);
            return Optional.empty();
        }

        try {
            CallSessionId sessionId = CallSessionId.fromString(callId);
            return Optional.of(new OperatorAudioTarget(operatorId, sessionId));
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid callId in operator bridge handshake URI: callId={}", callId);
            return Optional.empty();
        }
    }

    private static boolean isValidOperatorId(String operatorId) {
        if (operatorId == null
                || operatorId.isBlank()
                || operatorId.length() > MAX_OPERATOR_ID_LENGTH
                || "{operatorId}".equals(operatorId)
                || operatorId.indexOf('%') >= 0) {
            return false;
        }
        for (int i = 0; i < operatorId.length(); i++) {
            char ch = operatorId.charAt(i);
            if (Character.isWhitespace(ch) || ch == '/') {
                return false;
            }
        }
        return true;
    }
}
