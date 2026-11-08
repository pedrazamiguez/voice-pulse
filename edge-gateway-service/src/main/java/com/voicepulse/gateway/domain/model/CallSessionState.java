package com.voicepulse.gateway.domain.model;

import java.time.Instant;

/** Sealed interface representing the bounded lifecycle states of a telephony call session. */
public sealed interface CallSessionState
        permits CallSessionState.Connected,
                CallSessionState.Streaming,
                CallSessionState.Disconnected {

    /** Session is established and awaiting initial audio frames. */
    record Connected(Instant connectedAt) implements CallSessionState {}

    /** Session is actively streaming bidirectional audio frames. */
    record Streaming(Instant startedStreamingAt, long framesReceived) implements CallSessionState {}

    /** Session is terminated. */
    record Disconnected(Instant disconnectedAt, DisconnectReason reason)
            implements CallSessionState {}
}
