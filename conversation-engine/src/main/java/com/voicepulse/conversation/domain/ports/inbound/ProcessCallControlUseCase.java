package com.voicepulse.conversation.domain.ports.inbound;

import com.voicepulse.shared.model.CallId;

/**
 * Inbound use case for processing call control signaling events (connect, hangup, mute, barge-in).
 */
public interface ProcessCallControlUseCase {

    /**
     * Handles a hangup / call termination signaling event.
     *
     * @param callId identifier of the terminated call session
     */
    void handleHangup(CallId callId);
}
