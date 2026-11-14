package com.voicepulse.conversation.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.conversation.domain.model.OperatorHandoffReceipt;
import com.voicepulse.conversation.domain.model.OperatorQueueStatus;
import com.voicepulse.conversation.domain.ports.outbound.OperatorRoutingPort;
import com.voicepulse.conversation.infrastructure.mcp.config.McpClientProperties;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpQueueStatusRequestDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpQueueStatusResponseDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpTransferRequestDto;
import com.voicepulse.conversation.infrastructure.mcp.dto.McpTransferResponseDto;
import com.voicepulse.conversation.infrastructure.mcp.mapper.McpRoutingMapper;
import com.voicepulse.shared.model.CallId;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Secondary outbound adapter implementing {@link OperatorRoutingPort} by invoking {@code
 * check_queue_status} and {@code transfer_to_human_operator} on {@code mcp-telephony-routing} over
 * Java 21 Virtual Threads.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpRoutingClientAdapter implements OperatorRoutingPort {

    private static final String CHECK_QUEUE_STATUS_PATH = "/tools/check_queue_status";
    private static final String TRANSFER_TO_OPERATOR_PATH = "/tools/transfer_to_human_operator";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final @NonNull HttpClient mcpHttpClient;
    private final @NonNull ExecutorService mcpVirtualThreadExecutor;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull McpClientProperties properties;
    private final @NonNull McpRoutingMapper mapper;

    @Override
    public OperatorQueueStatus checkQueueStatus(String department) {
        Objects.requireNonNull(department, "department must not be null");
        if (department.isBlank()) {
            throw new IllegalArgumentException("department must not be blank");
        }
        try {
            return CompletableFuture.supplyAsync(
                            () -> executeCheckQueueStatus(department), mcpVirtualThreadExecutor)
                    .join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof McpClientException mcpEx) {
                throw mcpEx;
            }
            throw new McpClientException(
                    "Failed to execute check_queue_status on mcp-telephony-routing for department="
                            + department,
                    cause != null ? cause : ex);
        }
    }

    @Override
    public OperatorHandoffReceipt transferToHumanOperator(
            CallId callId, String department, UUID ticketId) {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(department, "department must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        if (department.isBlank()) {
            throw new IllegalArgumentException("department must not be blank");
        }
        try {
            return CompletableFuture.supplyAsync(
                            () -> executeTransfer(callId, department, ticketId),
                            mcpVirtualThreadExecutor)
                    .join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof McpClientException mcpEx) {
                throw mcpEx;
            }
            throw new McpClientException(
                    "Failed to execute transfer_to_human_operator on mcp-telephony-routing for"
                            + " callId="
                            + callId
                            + ", department="
                            + department
                            + ", ticketId="
                            + ticketId,
                    cause != null ? cause : ex);
        }
    }

    private OperatorQueueStatus executeCheckQueueStatus(String department) {
        URI endpoint = URI.create(properties.routingBaseUrl() + CHECK_QUEUE_STATUS_PATH);
        try {
            McpQueueStatusRequestDto requestDto = mapper.toQueueStatusRequest(department);
            String requestBody = objectMapper.writeValueAsString(requestDto);

            HttpRequest request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(properties.requestTimeout())
                            .header("Content-Type", CONTENT_TYPE_JSON)
                            .header("Accept", CONTENT_TYPE_JSON)
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();

            log.debug(
                    "Invoking mcp-telephony-routing check_queue_status: department={}, uri={}",
                    department,
                    endpoint);
            HttpResponse<String> response =
                    mcpHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 200 || status == 201) {
                McpQueueStatusResponseDto responseDto =
                        objectMapper.readValue(response.body(), McpQueueStatusResponseDto.class);
                if (responseDto == null) {
                    throw new McpClientException(
                            "mcp-telephony-routing returned empty queue status payload for"
                                    + " department="
                                    + department);
                }
                OperatorQueueStatus queueStatus = mapper.toQueueStatus(responseDto);
                log.info(
                        "Queue status retrieved via mcp-telephony-routing: department={},"
                            + " availableOperators={}, estimatedWaitSeconds={}, queuePosition={}",
                        queueStatus.department(),
                        queueStatus.availableOperators(),
                        queueStatus.estimatedWaitSeconds(),
                        queueStatus.queuePosition());
                return queueStatus;
            }

            throw new McpClientException(
                    "mcp-telephony-routing returned unexpected HTTP status "
                            + status
                            + " for check_queue_status department="
                            + department);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new McpClientException(
                    "Interrupted while calling mcp-telephony-routing check_queue_status for"
                            + " department="
                            + department,
                    ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new McpClientException(
                    "Error communicating with mcp-telephony-routing check_queue_status for"
                            + " department="
                            + department,
                    ex);
        }
    }

    private OperatorHandoffReceipt executeTransfer(
            CallId callId, String department, UUID ticketId) {
        URI endpoint = URI.create(properties.routingBaseUrl() + TRANSFER_TO_OPERATOR_PATH);
        try {
            McpTransferRequestDto requestDto =
                    mapper.toTransferRequest(callId, department, ticketId);
            String requestBody = objectMapper.writeValueAsString(requestDto);

            HttpRequest request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(properties.requestTimeout())
                            .header("Content-Type", CONTENT_TYPE_JSON)
                            .header("Accept", CONTENT_TYPE_JSON)
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();

            log.debug(
                    "Invoking mcp-telephony-routing transfer_to_human_operator: callId={},"
                            + " department={}, ticketId={}, uri={}",
                    callId,
                    department,
                    ticketId,
                    endpoint);
            HttpResponse<String> response =
                    mcpHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 200 || status == 201) {
                McpTransferResponseDto responseDto =
                        objectMapper.readValue(response.body(), McpTransferResponseDto.class);
                if (responseDto == null) {
                    throw new McpClientException(
                            "mcp-telephony-routing returned empty transfer payload for callId="
                                    + callId
                                    + ", ticketId="
                                    + ticketId);
                }
                OperatorHandoffReceipt receipt =
                        mapper.toHandoffReceipt(
                                responseDto, callId, department, ticketId, Instant.now());
                log.info(
                        "Call transferred via mcp-telephony-routing: callId={}, ticketId={},"
                                + " department={}, status={}, queuePosition={}, waitSeconds={}",
                        receipt.callId(),
                        receipt.ticketId(),
                        receipt.department(),
                        receipt.status(),
                        receipt.queuePosition(),
                        receipt.estimatedWaitSeconds());
                return receipt;
            }

            throw new McpClientException(
                    "mcp-telephony-routing returned unexpected HTTP status "
                            + status
                            + " for transfer_to_human_operator callId="
                            + callId
                            + ", department="
                            + department
                            + ", ticketId="
                            + ticketId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new McpClientException(
                    "Interrupted while calling mcp-telephony-routing transfer_to_human_operator for"
                            + " callId="
                            + callId
                            + ", ticketId="
                            + ticketId,
                    ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new McpClientException(
                    "Error communicating with mcp-telephony-routing transfer_to_human_operator for"
                            + " callId="
                            + callId
                            + ", ticketId="
                            + ticketId,
                    ex);
        }
    }
}
