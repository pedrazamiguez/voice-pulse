package com.voicepulse.routing.infrastructure.ws;

import com.voicepulse.routing.infrastructure.persistence.TicketPersistenceException;
import com.voicepulse.routing.infrastructure.redis.RedisRoutingException;
import com.voicepulse.routing.infrastructure.ws.dto.ApiErrorDto;
import java.time.Clock;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/**
 * Translates domain validation errors, state conflicts, and infrastructure failures into structured
 * {@link ApiErrorDto} JSON responses for {@link OperatorRestController}.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = OperatorRestController.class)
@RequiredArgsConstructor
public class OperatorRestExceptionHandler {

    private final @NonNull Clock clock;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorDto> handleBadRequest(
            IllegalArgumentException ex, ServerWebExchange exchange) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), exchange);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ApiErrorDto> handleInputException(
            ServerWebInputException ex, ServerWebExchange exchange) {
        String reason = ex.getReason() != null ? ex.getReason() : "Malformed request input";
        return buildErrorResponse(HttpStatus.BAD_REQUEST, reason, exchange);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorDto> handleStateConflict(
            IllegalStateException ex, ServerWebExchange exchange) {
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage(), exchange);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorDto> handleResponseStatus(
            ResponseStatusException ex, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus resolved = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        String message = ex.getReason() != null ? ex.getReason() : resolved.getReasonPhrase();
        return buildErrorResponse(resolved, message, exchange);
    }

    @ExceptionHandler({RedisRoutingException.class, TicketPersistenceException.class})
    public ResponseEntity<ApiErrorDto> handleInfrastructureFailure(
            RuntimeException ex, ServerWebExchange exchange) {
        log.error(
                "Infrastructure failure handling operator console request path={}",
                exchange.getRequest().getPath().value(),
                ex);
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), exchange);
    }

    private ResponseEntity<ApiErrorDto> buildErrorResponse(
            HttpStatus status, String message, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        ApiErrorDto body =
                new ApiErrorDto(
                        status.value(),
                        status.getReasonPhrase(),
                        message == null ? status.getReasonPhrase() : message,
                        path,
                        clock.instant());
        return ResponseEntity.status(status).body(body);
    }
}
