package com.voicepulse.shared.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallIdTest {

    @Test
    @DisplayName("valid UUID generates properly and preserves equality")
    void validUuidRoundtrip() {
        UUID original = UUID.randomUUID();
        CallId callId = new CallId(original);

        assertThat(callId.value()).isEqualTo(original);
        assertThat(callId.toString()).isEqualTo(original.toString());

        CallId parsed = CallId.fromString(original.toString());
        assertThat(parsed).isEqualTo(callId);
        assertThat(parsed.hashCode()).isEqualTo(callId.hashCode());
    }

    @Test
    @DisplayName("generate produces distinct valid CallId instances")
    void generateDistinctInstances() {
        CallId id1 = CallId.generate();
        CallId id2 = CallId.generate();

        assertThat(id1).isNotNull();
        assertThat(id2).isNotNull();
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("null UUID throws NullPointerException")
    void nullUuidThrowsException() {
        assertThatThrownBy(() -> new CallId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId UUID cannot be null");

        assertThatThrownBy(() -> CallId.fromString(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId string cannot be null");
    }
}
