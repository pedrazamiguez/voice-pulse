package com.voicepulse.routing.infrastructure.ws.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Locale;

/** Inbound JSON command envelope sent by the Operator Web Console browser client. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OperatorWsClientCommand(String type, Instant clientTime) {

    /** Returns normalized uppercase command type, or empty string if null. */
    public String normalizedType() {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }
}
