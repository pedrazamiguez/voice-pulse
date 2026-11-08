package com.voicepulse.gateway.domain.model;

/** Reason for call session disconnection. */
public enum DisconnectReason {
    /** Remote client hung up or closed the transport connection. */
    CLIENT_HANGUP,

    /** Call session timed out due to lack of audio frames or ping activity. */
    INACTIVITY_TIMEOUT,

    /** Transport protocol error (e.g., DoS frame size violation, invalid framing). */
    PROTOCOL_ERROR,

    /** Gateway node is shutting down. */
    SERVER_SHUTDOWN
}
