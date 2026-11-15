package com.voicepulse.routing.domain.model;

import java.util.Objects;

/**
 * Canonical disruption ticket and call-session lifecycle status codes shared across domain
 * transitions and durable persistence mapping.
 */
public enum HandoffTicketStatus {
    QUEUED,
    ASSIGNED,
    AUDIO_BRIDGED,
    COMPLETED,
    ABANDONED;

    /** Maps a sealed {@link HandoffLifecycleState} variant to its canonical status enum. */
    public static HandoffTicketStatus fromLifecycleState(HandoffLifecycleState state) {
        Objects.requireNonNull(state, "state cannot be null");
        return switch (state) {
            case HandoffLifecycleState.Queued ignored -> QUEUED;
            case HandoffLifecycleState.Assigned ignored -> ASSIGNED;
            case HandoffLifecycleState.AudioBridged ignored -> AUDIO_BRIDGED;
            case HandoffLifecycleState.Completed ignored -> COMPLETED;
            case HandoffLifecycleState.Abandoned ignored -> ABANDONED;
        };
    }
}
