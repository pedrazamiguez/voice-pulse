package com.voicepulse.routing.infrastructure.ws.dto;

import java.time.Instant;

/** Structured JSON error response returned by {@code OperatorRestExceptionHandler}. */
public record ApiErrorDto(
        int status, String error, String message, String path, Instant timestamp) {}
