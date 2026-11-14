package com.voicepulse.conversation.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.conversation.infrastructure.ai.config.AiInferenceProperties;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatCompletionRequest;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatCompletionResponse;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatMessageDto;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatToolDefinitionDto;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * HTTP client adapter invoking OpenAI/Ollama-compatible {@code /v1/chat/completions} endpoints on
 * Java 21 Virtual Threads.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleChatClient {

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final double DETERMINISTIC_TEMPERATURE = 0.0;

    private final @NonNull HttpClient aiHttpClient;
    private final @NonNull ExecutorService aiVirtualThreadExecutor;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull AiInferenceProperties properties;

    /**
     * Executes a synchronous-looking chat completion call offloaded onto a Java 21 Virtual Thread.
     *
     * @param messages conversation message history
     * @param tools tool schemas exposed to the model
     * @return deserialized completion response
     */
    public ChatCompletionResponse complete(
            List<ChatMessageDto> messages, List<ChatToolDefinitionDto> tools) {
        Objects.requireNonNull(messages, "messages cannot be null");
        Objects.requireNonNull(tools, "tools cannot be null");

        try {
            return CompletableFuture.supplyAsync(
                            () -> executeHttpCall(messages, tools), aiVirtualThreadExecutor)
                    .join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof AiInferenceException aiEx) {
                throw aiEx;
            }
            throw new AiInferenceException(
                    "Failed to execute chat completion against " + properties.endpointUrl(),
                    cause != null ? cause : ex);
        }
    }

    private ChatCompletionResponse executeHttpCall(
            List<ChatMessageDto> messages, List<ChatToolDefinitionDto> tools) {
        URI endpoint = URI.create(properties.endpointUrl());
        try {
            ChatCompletionRequest payload =
                    new ChatCompletionRequest(
                            properties.model(),
                            messages,
                            tools.isEmpty() ? null : tools,
                            DETERMINISTIC_TEMPERATURE,
                            false);
            String requestBody = objectMapper.writeValueAsString(payload);

            HttpRequest.Builder requestBuilder =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(properties.timeout())
                            .header("Content-Type", CONTENT_TYPE_JSON)
                            .header("Accept", CONTENT_TYPE_JSON)
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody));

            if (!properties.apiKey().isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + properties.apiKey());
            }

            log.debug(
                    "Invoking live LLM completion: model={}, uri={}, messages={}",
                    properties.model(),
                    endpoint,
                    messages.size());
            HttpResponse<String> response =
                    aiHttpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                ChatCompletionResponse parsed =
                        objectMapper.readValue(response.body(), ChatCompletionResponse.class);
                if (parsed == null || parsed.choices().isEmpty()) {
                    throw new AiInferenceException(
                            "Live LLM returned empty choices from " + endpoint);
                }
                return parsed;
            }

            throw new AiInferenceException(
                    "Live LLM endpoint returned unexpected HTTP status "
                            + status
                            + " from "
                            + endpoint);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AiInferenceException("Interrupted while calling live LLM at " + endpoint, ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new AiInferenceException(
                    "Error communicating with live LLM endpoint " + endpoint, ex);
        }
    }
}
