package com.voicepulse.conversation.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.conversation.domain.ports.outbound.CustomerLookupPort;
import com.voicepulse.conversation.infrastructure.mcp.config.McpClientProperties;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpCustomerLookupRequestDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpCustomerLookupResponseDto;
import com.voicepulse.conversation.infrastructure.mcp.mapper.McpCrmMapper;
import com.voicepulse.shared.model.Pnr;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Secondary outbound adapter implementing {@link CustomerLookupPort} by invoking the {@code
 * lookup_customer} tool on {@code mcp-crm-server} over Java 21 Virtual Threads.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpCrmClientAdapter implements CustomerLookupPort {

    private static final String LOOKUP_CUSTOMER_PATH = "/tools/lookup_customer";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final @NonNull HttpClient mcpHttpClient;
    private final @NonNull ExecutorService mcpVirtualThreadExecutor;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull McpClientProperties properties;
    private final @NonNull McpCrmMapper mapper;

    @Override
    public Optional<CustomerProfile> lookupCustomerByPnr(Pnr pnr) {
        Objects.requireNonNull(pnr, "pnr must not be null");
        try {
            return CompletableFuture.supplyAsync(() -> executeLookup(pnr), mcpVirtualThreadExecutor)
                    .join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof McpClientException mcpEx) {
                throw mcpEx;
            }
            throw new McpClientException(
                    "Failed to execute lookup_customer on mcp-crm-server for pnr=" + pnr,
                    cause != null ? cause : ex);
        }
    }

    private Optional<CustomerProfile> executeLookup(Pnr pnr) {
        URI endpoint = URI.create(properties.crmBaseUrl() + LOOKUP_CUSTOMER_PATH);
        try {
            String requestBody =
                    objectMapper.writeValueAsString(new McpCustomerLookupRequestDto(pnr.value()));

            HttpRequest request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(properties.requestTimeout())
                            .header("Content-Type", CONTENT_TYPE_JSON)
                            .header("Accept", CONTENT_TYPE_JSON)
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();

            log.debug("Invoking mcp-crm-server lookup_customer: pnr={}, uri={}", pnr, endpoint);
            HttpResponse<String> response =
                    mcpHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 200) {
                McpCustomerLookupResponseDto dto =
                        objectMapper.readValue(response.body(), McpCustomerLookupResponseDto.class);
                CustomerProfile profile =
                        (dto.pnr() != null && !dto.pnr().isBlank())
                                ? mapper.toDomain(dto)
                                : mapper.toDomain(dto, pnr);
                log.info(
                        "Customer profile resolved via mcp-crm-server: pnr={}, tier={}, flight={}",
                        pnr,
                        profile.frequentFlyerTier(),
                        profile.activeItinerary().flightNumber());
                return Optional.of(profile);
            }

            if (status == 404) {
                log.info("PNR not found on mcp-crm-server: pnr={}", pnr);
                return Optional.empty();
            }

            throw new McpClientException(
                    "mcp-crm-server returned unexpected HTTP status " + status + " for pnr=" + pnr);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new McpClientException(
                    "Interrupted while calling mcp-crm-server for pnr=" + pnr, ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new McpClientException(
                    "Error communicating with mcp-crm-server for pnr=" + pnr, ex);
        }
    }
}
