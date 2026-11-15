package com.voicepulse.routing.domain.model;

/** Real-time availability states for a human operator tracked in Redis presence. */
public enum OperatorAvailabilityStatus {
    AVAILABLE,
    BUSY,
    PAUSED,
    OFFLINE;

    /** Returns {@code true} only when the operator can accept a new call assignment. */
    public boolean canAcceptCall() {
        return this == AVAILABLE;
    }
}
