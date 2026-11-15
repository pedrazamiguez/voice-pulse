package com.voicepulse.routing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("OperatorId Value Object Domain Tests")
class OperatorIdTest {

    @Test
    @DisplayName("Should trim valid operatorId and expose value via toString")
    void shouldTrimValidOperatorId() {
        OperatorId id = OperatorId.fromString("  op-alpha-01  ");

        assertThat(id.value()).isEqualTo("op-alpha-01");
        assertThat(id).hasToString("op-alpha-01");
    }

    @Test
    @DisplayName("Should reject null operatorId")
    void shouldRejectNullOperatorId() {
        assertThatThrownBy(() -> new OperatorId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("operatorId cannot be null");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "op with spaces", "op\t01"})
    @DisplayName("Should reject blank or whitespace-containing operatorId")
    void shouldRejectBlankOrWhitespaceOperatorId(String invalid) {
        assertThatThrownBy(() -> new OperatorId(invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject operatorId exceeding 64 characters")
    void shouldRejectOverlongOperatorId() {
        String overlong = "a".repeat(65);
        assertThatThrownBy(() -> new OperatorId(overlong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds max length of 64");
    }
}
