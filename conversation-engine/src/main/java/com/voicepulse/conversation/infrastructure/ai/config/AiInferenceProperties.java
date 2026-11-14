package com.voicepulse.conversation.infrastructure.ai.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties mapped to {@code voicepulse.conversation.ai}.
 *
 * <p>Validates conversational AI strategy mode, inference endpoint URL, model identifier, and
 * request timeout at bootstrap (fail-fast). Default values are provided exclusively via {@code
 * ${ENV_VAR:fallback}} expressions in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "voicepulse.conversation.ai")
public record AiInferenceProperties(
        AiProcessingMode mode, String endpointUrl, String model, Duration timeout, String apiKey) {

    public AiInferenceProperties {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        endpointUrl = validateUrl(endpointUrl);
        model = validateModel(model);
        validateTimeout(timeout);
        apiKey = (apiKey == null) ? "" : apiKey.trim();
    }

    private static String validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("endpointUrl must not be null or blank");
        }
        String trimmed = url.trim();
        URI parsed;
        try {
            parsed = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("endpointUrl must be a valid URI: " + trimmed, ex);
        }
        String scheme = parsed.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                || parsed.getHost() == null
                || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "endpointUrl must be a valid HTTP/HTTPS URL: " + trimmed);
        }
        return trimmed;
    }

    private static String validateModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be null or blank");
        }
        return model.trim();
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive, but was: " + timeout);
        }
    }
}
