package com.voicepulse.gateway.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Sealed interface representing the bounded lifecycle states of a telephony call session. */
public sealed interface CallSessionState
        permits CallSessionState.Connected,
                CallSessionState.Streaming,
                CallSessionState.Bridged,
                CallSessionState.Disconnected {

    /** Session is established and awaiting initial audio frames. */
    record Connected(Instant connectedAt) implements CallSessionState {}

    /** Session is actively streaming bidirectional audio frames. */
    record Streaming(Instant startedStreamingAt, long framesReceived) implements CallSessionState {}

    /** Session is bridged directly to an assigned human operator WebSocket endpoint. */
    record Bridged(String operatorId, String bridgeEndpoint, Instant bridgedAt)
            implements CallSessionState {

        public Bridged {
            if (operatorId == null || operatorId.isBlank()) {
                throw new IllegalArgumentException("operatorId cannot be null or blank");
            }
            if (bridgeEndpoint == null || bridgeEndpoint.isBlank()) {
                throw new IllegalArgumentException("bridgeEndpoint cannot be null or blank");
            }
            Objects.requireNonNull(bridgedAt, "bridgedAt cannot be null");
        }
    }

    /** Session is terminated. */
    record Disconnected(Instant disconnectedAt, DisconnectReason reason)
            implements CallSessionState {}
}
