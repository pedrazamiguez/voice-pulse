package com.voicepulse.gateway.infrastructure.persistence.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.gateway.domain.model.CallSession;
import com.voicepulse.shared.model.CallSessionId;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemorySessionRegistryTest {

    private InMemorySessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemorySessionRegistry();
    }

    @Test
    @DisplayName("save and findById successfully tracks sessions")
    void saveAndFindById_tracksSession() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now());

        registry.save(session);

        assertThat(registry.findById(sessionId)).contains(session);
        assertThat(registry.exists(sessionId)).isTrue();
        assertThat(registry.activeSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("remove eliminates session from registry")
    void remove_eliminatesSession() {
        CallSessionId sessionId = CallSessionId.generate();
        CallSession session = CallSession.start(sessionId, Instant.now());

        registry.save(session);
        registry.remove(sessionId);

        assertThat(registry.findById(sessionId)).isEmpty();
        assertThat(registry.exists(sessionId)).isFalse();
        assertThat(registry.activeSessionCount()).isZero();
    }

    @Test
    @DisplayName("null arguments throw NullPointerException")
    void nullArguments_throwException() {
        assertThatThrownBy(() -> registry.save(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.findById(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.remove(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.exists(null)).isInstanceOf(NullPointerException.class);
    }
}
