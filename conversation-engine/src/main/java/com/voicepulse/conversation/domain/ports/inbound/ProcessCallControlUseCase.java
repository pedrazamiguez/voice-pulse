package com.voicepulse.conversation.domain.ports.inbound;

import com.voicepulse.shared.model.CallId;
import java.time.Instant;

/**
 * Inbound use case for processing call control signaling events (connect, hangup, mute, barge-in).
 */
public interface ProcessCallControlUseCase {

    /**
     * Handles a call start / connection establishment signaling event.
     *
     * @param callId identifier of the established call session
     * @param startedAt timestamp of session start
     */
    void handleCallStarted(CallId callId, Instant startedAt);

    /**
     * Handles a caller barge-in interruption signaling event.
     *
     * @param callId identifier of the interrupted call session
     * @param occurredAt timestamp when barge-in occurred
     */
    void handleBargeIn(CallId callId, Instant occurredAt);

    /**
     * Handles a hangup / call termination signaling event.
     *
     * @param callId identifier of the terminated call session
     */
    void handleHangup(CallId callId);
}
