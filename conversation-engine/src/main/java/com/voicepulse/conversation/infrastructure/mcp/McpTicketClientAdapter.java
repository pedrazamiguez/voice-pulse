package com.voicepulse.conversation.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.TicketSubmissionReceipt;
import com.voicepulse.conversation.domain.ports.outbound.DisruptionTicketPort;
import com.voicepulse.conversation.infrastructure.mcp.config.McpClientProperties;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpFinalizeTicketRequestDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpFinalizeTicketResponseDto;
import com.voicepulse.conversation.infrastructure.mcp.mapper.McpTicketMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Secondary outbound adapter implementing {@link DisruptionTicketPort} by invoking the {@code
 * finalize_ticket} tool on {@code mcp-ticket-server} over Java 21 Virtual Threads.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpTicketClientAdapter implements DisruptionTicketPort {

    private static final String FINALIZE_TICKET_PATH = "/tools/finalize_ticket";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final @NonNull HttpClient mcpHttpClient;
    private final @NonNull ExecutorService mcpVirtualThreadExecutor;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull McpClientProperties properties;
    private final @NonNull McpTicketMapper mapper;

    @Override
    public TicketSubmissionReceipt finalizeTicket(AirlineDisruptionTicket ticket) {
        Objects.requireNonNull(ticket, "ticket must not be null");
        try {
            return CompletableFuture.supplyAsync(
                            () -> executeFinalizeTicket(ticket), mcpVirtualThreadExecutor)
                    .join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof McpClientException mcpEx) {
                throw mcpEx;
            }
            throw new McpClientException(
                    "Failed to execute finalize_ticket on mcp-ticket-server for ticketId="
                            + ticket.ticketId(),
                    cause != null ? cause : ex);
        }
    }

    private TicketSubmissionReceipt executeFinalizeTicket(AirlineDisruptionTicket ticket) {
        URI endpoint = URI.create(properties.ticketBaseUrl() + FINALIZE_TICKET_PATH);
        try {
            McpFinalizeTicketRequestDto requestDto = mapper.toFinalizeRequest(ticket);
            String requestBody = objectMapper.writeValueAsString(requestDto);

            HttpRequest request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(properties.requestTimeout())
                            .header("Content-Type", CONTENT_TYPE_JSON)
                            .header("Accept", CONTENT_TYPE_JSON)
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();

            log.debug(
                    "Invoking mcp-ticket-server finalize_ticket: ticketId={}, pnr={}, uri={}",
                    ticket.ticketId(),
                    ticket.pnr(),
                    endpoint);
            HttpResponse<String> response =
                    mcpHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 200 || status == 201) {
                McpFinalizeTicketResponseDto responseDto =
                        objectMapper.readValue(response.body(), McpFinalizeTicketResponseDto.class);
                TicketSubmissionReceipt receipt = mapper.toReceipt(responseDto);
                log.info(
                        "Disruption ticket finalized via mcp-ticket-server: ticketId={}, pnr={},"
                                + " category={}, priority={}, status={}",
                        receipt.ticketId(),
                        receipt.pnr(),
                        receipt.category(),
                        receipt.priority(),
                        receipt.status());
                return receipt;
            }

            throw new McpClientException(
                    "mcp-ticket-server returned unexpected HTTP status "
                            + status
                            + " for ticketId="
                            + ticket.ticketId());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new McpClientException(
                    "Interrupted while calling mcp-ticket-server for ticketId=" + ticket.ticketId(),
                    ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new McpClientException(
                    "Error communicating with mcp-ticket-server for ticketId=" + ticket.ticketId(),
                    ex);
        }
    }
}
