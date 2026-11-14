package com.voicepulse.conversation.infrastructure.mcp.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties mapped to {@code voicepulse.conversation.mcp}.
 *
 * <p>Validates MCP tool server base URLs and HTTP timeouts at bootstrap (fail-fast). Default values
 * are provided via {@code ${ENV_VAR:fallback}} expressions in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "voicepulse.conversation.mcp")
public record McpClientProperties(
        String crmBaseUrl,
        String ticketBaseUrl,
        String routingBaseUrl,
        Duration connectTimeout,
        Duration requestTimeout) {

    public McpClientProperties {
        crmBaseUrl = validateAndNormalizeUrl(crmBaseUrl, "crmBaseUrl");
        ticketBaseUrl = validateAndNormalizeUrl(ticketBaseUrl, "ticketBaseUrl");
        routingBaseUrl = validateAndNormalizeUrl(routingBaseUrl, "routingBaseUrl");
        validateTimeout(connectTimeout, "connectTimeout");
        validateTimeout(requestTimeout, "requestTimeout");
    }

    private static String validateAndNormalizeUrl(String url, String fieldName) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        String trimmed = url.trim();
        URI parsed;
        try {
            parsed = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid URI: " + trimmed, ex);
        }
        String scheme = parsed.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                || parsed.getHost() == null
                || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must be a valid HTTP/HTTPS URL: " + trimmed);
        }
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void validateTimeout(Duration timeout, String fieldName) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    fieldName + " must be positive, but was: " + timeout);
        }
    }
}
