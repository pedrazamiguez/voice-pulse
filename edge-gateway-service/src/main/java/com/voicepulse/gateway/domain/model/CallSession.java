package com.voicepulse.gateway.domain.model;

import com.voicepulse.gateway.domain.exception.IllegalSessionStateException;
import com.voicepulse.gateway.domain.exception.InvalidAudioFrameException;
import com.voicepulse.shared.model.AudioChunk;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure domain aggregate managing call session lifecycle transitions and monotonic frame ordering.
 *
 * <p>Contains zero framework dependencies.
 */
public class CallSession {

    private final CallSessionId id;
    private final Instant createdAt;
    private CallSessionState state;
    private long lastSequenceNumber;
    private Instant lastActivityAt;

    private CallSession(CallSessionId id, Instant connectedAt) {
        this.id = Objects.requireNonNull(id, "sessionId cannot be null");
        this.createdAt = Objects.requireNonNull(connectedAt, "connectedAt cannot be null");
        this.lastActivityAt = connectedAt;
        this.lastSequenceNumber = -1L;
        this.state = new CallSessionState.Connected(connectedAt);
    }

    /** Factory method creating a new session in {@link CallSessionState.Connected} state. */
    public static CallSession start(CallSessionId id, Instant connectedAt) {
        return new CallSession(id, connectedAt);
    }

    /**
     * Ingests an incoming audio chunk, verifying monotonic sequence order and updating aggregate
     * state.
     *
     * @param chunk incoming audio chunk
     * @throws IllegalSessionStateException if session is terminated
     * @throws InvalidAudioFrameException if sequence number is not strictly monotonic
     * @throws IllegalArgumentException if session ID does not match
     */
    public synchronized void acceptChunk(AudioChunk chunk) {
        Objects.requireNonNull(chunk, "chunk cannot be null");

        if (!this.id.equals(chunk.sessionId())) {
            throw new IllegalArgumentException(
                    "Session ID mismatch: expected " + this.id + " but got " + chunk.sessionId());
        }

        if (this.state instanceof CallSessionState.Disconnected) {
            throw new IllegalSessionStateException(
                    "Cannot process audio chunk on terminated session: " + this.id);
        }

        if (chunk.sequenceNumber() <= this.lastSequenceNumber) {
            throw new InvalidAudioFrameException(
                    "Out-of-order sequence number: received "
                            + chunk.sequenceNumber()
                            + ", expected > "
                            + this.lastSequenceNumber);
        }

        this.lastSequenceNumber = chunk.sequenceNumber();
        this.lastActivityAt = chunk.timestamp();

        if (this.state instanceof CallSessionState.Connected) {
            this.state = new CallSessionState.Streaming(chunk.timestamp(), 1L);
        } else if (this.state instanceof CallSessionState.Streaming current) {
            this.state =
                    new CallSessionState.Streaming(
                            current.startedStreamingAt(), current.framesReceived() + 1L);
        }
    }

    /**
     * Terminates the call session with the given reason.
     *
     * @param reason reason for disconnection
     * @param disconnectedAt timestamp of disconnection
     */
    public synchronized void terminate(DisconnectReason reason, Instant disconnectedAt) {
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(disconnectedAt, "disconnectedAt cannot be null");

        if (this.state instanceof CallSessionState.Disconnected) {
            return; // Idempotent
        }

        this.lastActivityAt = disconnectedAt;
        this.state = new CallSessionState.Disconnected(disconnectedAt, reason);
    }

    public CallSessionId id() {
        return id;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized CallSessionState state() {
        return state;
    }

    public synchronized long lastSequenceNumber() {
        return lastSequenceNumber;
    }

    public synchronized Instant lastActivityAt() {
        return lastActivityAt;
    }
}
