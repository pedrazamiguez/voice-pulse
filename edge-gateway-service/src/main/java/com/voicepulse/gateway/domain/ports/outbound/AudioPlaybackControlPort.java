package com.voicepulse.gateway.domain.ports.outbound;

import com.voicepulse.shared.model.CallSessionId;

/**
 * Outbound (driven) SPI port for controlling downstream audio playback and transport queues (e.g.
 * WebSocket client notification, Netty buffer flushing).
 */
public interface AudioPlaybackControlPort {

    /**
     * Commands transport layer to purge queued audio frames and emit interruption signaling.
     *
     * @param sessionId unique call session identifier
     */
    void interruptPlayback(CallSessionId sessionId);
}
