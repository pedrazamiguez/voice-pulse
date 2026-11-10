package com.voicepulse.conversation.application.registry;

import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.conversation.domain.vad.TrailingSilenceEvaluator;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Thread-safe registry coordinator managing active {@link ConversationSession} aggregates and
 * per-session DSP {@link TrailingSilenceEvaluator} instances.
 *
 * <p>Enforces Single Responsibility Principle (SRP) by decoupling session lifecycle management and
 * memory caching from algorithmic conversational orchestration.
 */
@Component
public class ConversationSessionRegistry {

    private final ConcurrentHashMap<CallId, ConversationSession> sessions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CallId, TrailingSilenceEvaluator> trailingSilenceEvaluators =
            new ConcurrentHashMap<>();

    /**
     * Atomically retrieves or initializes a conversation session for the given call identifier.
     *
     * @param callId unique call identifier
     * @param connectedAt session initialization timestamp
     * @return active conversation session aggregate root
     */
    public ConversationSession getOrCreate(CallId callId, Instant connectedAt) {
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(connectedAt, "connectedAt cannot be null");
        return sessions.computeIfAbsent(callId, id -> ConversationSession.start(id, connectedAt));
    }

    /**
     * Finds an active conversation session if present.
     *
     * @param callId unique call identifier
     * @return optional containing the active session, or empty if not present
     */
    public Optional<ConversationSession> find(CallId callId) {
        Objects.requireNonNull(callId, "callId cannot be null");
        return Optional.ofNullable(sessions.get(callId));
    }

    /**
     * Evicts and cleans up the active session and associated DSP evaluators.
     *
     * @param callId unique call identifier
     * @return optional containing the removed session, or empty if not present
     */
    public Optional<ConversationSession> remove(CallId callId) {
        Objects.requireNonNull(callId, "callId cannot be null");
        trailingSilenceEvaluators.remove(callId);
        return Optional.ofNullable(sessions.remove(callId));
    }

    /**
     * Atomically retrieves or instantiates an isolated {@link TrailingSilenceEvaluator} for the
     * call.
     *
     * @param callId unique call identifier
     * @return per-session trailing silence evaluator
     */
    public TrailingSilenceEvaluator getOrCreateTrailingSilenceEvaluator(CallId callId) {
        Objects.requireNonNull(callId, "callId cannot be null");
        return trailingSilenceEvaluators.computeIfAbsent(
                callId, id -> new TrailingSilenceEvaluator());
    }

    /** Returns total number of active conversation sessions in the registry. */
    public int activeSessionCount() {
        return sessions.size();
    }

    /** Clears all registered sessions and evaluators (useful for test resets). */
    public void clear() {
        trailingSilenceEvaluators.clear();
        sessions.clear();
    }
}
