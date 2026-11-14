package com.voicepulse.routing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("SkillGroup Domain Enum Tests")
class SkillGroupTest {

    @Test
    @DisplayName("Should preserve strict priority score hierarchy across skill tiers")
    void shouldPreservePriorityHierarchy() {
        assertThat(SkillGroup.DISRUPTION_DESK_GOLD.defaultPriorityScore())
                .isGreaterThan(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY.defaultPriorityScore());
        assertThat(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY.defaultPriorityScore())
                .isGreaterThan(SkillGroup.DISRUPTION_DESK_PRIORITY.defaultPriorityScore());
        assertThat(SkillGroup.DISRUPTION_DESK_PRIORITY.defaultPriorityScore())
                .isGreaterThan(SkillGroup.DISRUPTION_DESK_STANDARD.defaultPriorityScore());
    }

    @Test
    @DisplayName("Should parse trimmed and case-insensitive skill group codes")
    void shouldParseTrimmedAndCaseInsensitiveCodes() {
        assertThat(SkillGroup.fromCode("  disruption_desk_gold "))
                .isEqualTo(SkillGroup.DISRUPTION_DESK_GOLD);
        assertThat(SkillGroup.fromCode("DISRUPTION_DESK_ACCESSIBILITY"))
                .isEqualTo(SkillGroup.DISRUPTION_DESK_ACCESSIBILITY);
    }

    @Test
    @DisplayName("Should reject null code")
    void shouldRejectNullCode() {
        assertThatThrownBy(() -> SkillGroup.fromCode(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("skillGroup code cannot be null");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "UNKNOWN_DESK"})
    @DisplayName("Should reject blank or unknown skill group codes")
    void shouldRejectInvalidCodes(String invalidCode) {
        assertThatThrownBy(() -> SkillGroup.fromCode(invalidCode))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
