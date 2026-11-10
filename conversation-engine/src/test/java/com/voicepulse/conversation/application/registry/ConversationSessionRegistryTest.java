package com.voicepulse.conversation.application.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.conversation.domain.vad.TrailingSilenceEvaluator;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConversationSessionRegistry Unit Tests")
class ConversationSessionRegistryTest {

    private ConversationSessionRegistry registry;
    private CallId callId;
    private Instant now;

    @BeforeEach
    void setUp() {
        registry = new ConversationSessionRegistry();
        callId = CallId.generate();
        now = Instant.now();
    }

    @Test
    @DisplayName("Creates and retrieves conversation session atomically")
    void getOrCreate_createsNewSession() {
        ConversationSession session = registry.getOrCreate(callId, now);

        assertThat(session).isNotNull();
        assertThat(session.callId()).isEqualTo(callId);
        assertThat(registry.find(callId)).contains(session);
        assertThat(registry.activeSessionCount()).isEqualTo(1);

        // Subsequent call returns the existing session
        ConversationSession sameSession = registry.getOrCreate(callId, now);
        assertThat(sameSession).isSameAs(session);
        assertThat(registry.activeSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Removes session and associated VAD evaluators cleanly")
    void remove_cleansUpSessionAndEvaluators() {
        registry.getOrCreate(callId, now);
        TrailingSilenceEvaluator evaluator = registry.getOrCreateTrailingSilenceEvaluator(callId);
        assertThat(evaluator).isNotNull();

        Optional<ConversationSession> removed = registry.remove(callId);

        assertThat(removed).isPresent();
        assertThat(removed.get().callId()).isEqualTo(callId);
        assertThat(registry.find(callId)).isEmpty();
        assertThat(registry.activeSessionCount()).isZero();
    }

    @Test
    @DisplayName("Retrieves or creates isolated TrailingSilenceEvaluator per callId")
    void getOrCreateTrailingSilenceEvaluator_returnsConsistentInstance() {
        TrailingSilenceEvaluator evaluator1 = registry.getOrCreateTrailingSilenceEvaluator(callId);
        TrailingSilenceEvaluator evaluator2 = registry.getOrCreateTrailingSilenceEvaluator(callId);

        assertThat(evaluator1).isSameAs(evaluator2);

        CallId otherCallId = CallId.generate();
        TrailingSilenceEvaluator otherEvaluator =
                registry.getOrCreateTrailingSilenceEvaluator(otherCallId);
        assertThat(otherEvaluator).isNotSameAs(evaluator1);
    }

    @Test
    @DisplayName("Supports concurrent session creation without race conditions")
    void concurrentGetOrCreate_isThreadSafe() throws InterruptedException {
        int threadCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(
                    () -> {
                        try {
                            registry.getOrCreate(callId, now);
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        boolean completed = latch.await(2, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(registry.activeSessionCount()).isEqualTo(1);
        assertThat(registry.find(callId)).isPresent();
    }

    @Test
    @DisplayName("Throws NullPointerException on null parameters")
    void methods_rejectNullArguments() {
        assertThatThrownBy(() -> registry.getOrCreate(null, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");

        assertThatThrownBy(() -> registry.getOrCreate(callId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("connectedAt");

        assertThatThrownBy(() -> registry.find(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");

        assertThatThrownBy(() -> registry.remove(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");

        assertThatThrownBy(() -> registry.getOrCreateTrailingSilenceEvaluator(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");
    }
}
