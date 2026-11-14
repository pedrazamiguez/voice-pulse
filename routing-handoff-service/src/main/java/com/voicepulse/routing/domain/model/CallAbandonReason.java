package com.voicepulse.routing.domain.model;

/** Standard abandonment reasons recorded when a caller disconnects before handoff completion. */
public enum CallAbandonReason {
    CALLER_DISCONNECTED;

    /** Returns trimmed reason or {@link #CALLER_DISCONNECTED} when null or blank. */
    public static String normalizeOrDefault(String reason) {
        if (reason == null || reason.isBlank()) {
            return CALLER_DISCONNECTED.name();
        }
        return reason.trim();
    }
}
