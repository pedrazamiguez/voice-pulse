package com.voicepulse.gateway.domain.ports.inbound;

import com.voicepulse.shared.model.CallSessionId;

/**
 * Inbound (driving) use case for immediately interrupting active audio playback on an established
 * call session and purging downstream buffers (barge-in).
 */
public interface InterruptPlaybackUseCase {

    /**
     * Interrupts active audio playback for the given session and purges queued buffers.
     *
     * @param sessionId unique call session identifier
     */
    void interruptPlayback(CallSessionId sessionId);
}
