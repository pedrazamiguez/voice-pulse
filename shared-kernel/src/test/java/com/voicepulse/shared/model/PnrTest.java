package com.voicepulse.shared.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PnrTest {

    @Test
    @DisplayName("valid 6-character PNR is accepted and normalized to uppercase")
    void validPnrAccepted() {
        Pnr pnr = new Pnr("ab12cd");

        assertThat(pnr.value()).isEqualTo("AB12CD");
        assertThat(pnr.toString()).isEqualTo("AB12CD");
    }

    @Test
    @DisplayName("whitespace around PNR is trimmed before validation")
    void pnrWithWhitespaceTrimmed() {
        Pnr pnr = new Pnr("  XY89ZZ  ");

        assertThat(pnr.value()).isEqualTo("XY89ZZ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "AB12C", "AB12CDE", "AB-12C", "AB!2CD", "12345 "})
    @DisplayName("invalid PNR lengths and characters throw IllegalArgumentException")
    void invalidPnrRejected(String invalidPnr) {
        assertThatThrownBy(() -> new Pnr(invalidPnr))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PNR must be exactly 6 alphanumeric characters");
    }

    @Test
    @DisplayName("null PNR throws NullPointerException")
    void nullPnrThrowsException() {
        assertThatThrownBy(() -> new Pnr(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("PNR value cannot be null");
    }

    @Test
    @DisplayName("toNatoPhonetic expands alphanumeric PNR correctly per RFC-0003")
    void natoPhoneticExpansion() {
        Pnr pnr = new Pnr("AB7DF9");

        assertThat(pnr.toNatoPhonetic()).isEqualTo("Alpha Bravo Seven Delta Foxtrot Nine");
    }

    @Test
    @DisplayName("toNatoPhonetic expands all 10 digits and selected letters")
    void natoPhoneticAllDigits() {
        Pnr pnr = new Pnr("012345");

        assertThat(pnr.toNatoPhonetic()).isEqualTo("Zero One Two Three Four Five");
    }
}
