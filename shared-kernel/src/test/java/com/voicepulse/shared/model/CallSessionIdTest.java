package com.voicepulse.shared.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallSessionIdTest {

    @Test
    @DisplayName("valid UUID generates properly and preserves equality")
    void validUuidRoundtrip() {
        UUID original = UUID.randomUUID();
        CallSessionId sessionId = new CallSessionId(original);

        assertThat(sessionId.value()).isEqualTo(original);
        assertThat(sessionId.toString()).isEqualTo(original.toString());

        CallSessionId parsed = CallSessionId.fromString(original.toString());
        assertThat(parsed).isEqualTo(sessionId);
        assertThat(parsed.hashCode()).isEqualTo(sessionId.hashCode());
    }

    @Test
    @DisplayName("generate produces distinct valid CallSessionId instances")
    void generateDistinctInstances() {
        CallSessionId id1 = CallSessionId.generate();
        CallSessionId id2 = CallSessionId.generate();

        assertThat(id1).isNotNull();
        assertThat(id2).isNotNull();
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("null UUID throws NullPointerException")
    void nullUuidThrowsException() {
        assertThatThrownBy(() -> new CallSessionId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callSessionId UUID cannot be null");

        assertThatThrownBy(() -> CallSessionId.fromString(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callSessionId string cannot be null");
    }

    @Test
    @DisplayName("converts bidirectionally to and from CallId")
    void callIdInteroperability() {
        CallId callId = CallId.generate();
        CallSessionId sessionId = CallSessionId.fromCallId(callId);

        assertThat(sessionId.value()).isEqualTo(callId.value());
        assertThat(sessionId.toCallId()).isEqualTo(callId);
        assertThat(callId.toSessionId()).isEqualTo(sessionId);

        CallSessionId fromMethod = CallId.fromSessionId(sessionId).toSessionId();
        assertThat(fromMethod).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("null CallId throws NullPointerException when converting")
    void nullCallIdThrowsException() {
        assertThatThrownBy(() -> CallSessionId.fromCallId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId cannot be null");

        assertThatThrownBy(() -> CallId.fromSessionId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sessionId cannot be null");
    }
}
