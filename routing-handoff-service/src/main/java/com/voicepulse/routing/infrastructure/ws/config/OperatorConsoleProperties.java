package com.voicepulse.routing.infrastructure.ws.config;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fail-fast configuration properties bound to {@code voicepulse.routing.console}.
 *
 * <p>Default values are supplied exclusively via {@code ${ENV_VAR:fallback}} expressions in {@code
 * application.yml}. Invalid or missing values fail application startup immediately.
 */
@ConfigurationProperties(prefix = "voicepulse.routing.console")
public record OperatorConsoleProperties(
        String gatewayWsBaseUrl, Duration heartbeatInterval, int outboundBufferCapacity) {

    public OperatorConsoleProperties {
        gatewayWsBaseUrl = normalizeGatewayWsBaseUrl(gatewayWsBaseUrl);
        validatePositiveDuration(heartbeatInterval, "heartbeatInterval");
        if (outboundBufferCapacity <= 0) {
            throw new IllegalArgumentException(
                    "outboundBufferCapacity must be positive, but was: " + outboundBufferCapacity);
        }
    }

    /**
     * Resolves a full WebSocket audio bridge URL from a relative bridge endpoint path or full URL.
     *
     * @param bridgeEndpoint relative path (e.g. {@code /ws/operators/op-1/calls/.../audio}) or full
     *     {@code ws://}/{@code wss://} URI
     * @return full WebSocket audio bridge URL
     */
    public String resolveBridgeUrl(String bridgeEndpoint) {
        if (bridgeEndpoint == null || bridgeEndpoint.isBlank()) {
            return null;
        }
        String trimmed = bridgeEndpoint.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ws://") || lower.startsWith("wss://")) {
            return trimmed;
        }
        return trimmed.startsWith("/")
                ? gatewayWsBaseUrl + trimmed
                : gatewayWsBaseUrl + "/" + trimmed;
    }

    private static String normalizeGatewayWsBaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("gatewayWsBaseUrl must not be null or blank");
        }
        String trimmed = rawUrl.trim();
        URI uri = URI.create(trimmed);
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException(
                    "gatewayWsBaseUrl must specify ws or wss scheme: " + trimmed);
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"ws".equals(normalizedScheme) && !"wss".equals(normalizedScheme)) {
            throw new IllegalArgumentException(
                    "gatewayWsBaseUrl scheme must be ws or wss, but was: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("gatewayWsBaseUrl must include a host: " + trimmed);
        }
        while (trimmed.endsWith("/") && trimmed.length() > normalizedScheme.length() + 3) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void validatePositiveDuration(Duration duration, String fieldName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    fieldName + " must be positive, but was: " + duration);
        }
    }
}
