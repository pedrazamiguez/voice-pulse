package com.voicepulse.shared.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FlightNumberTest {

    @Test
    @DisplayName("valid flight number format is accepted and normalized")
    void validFlightNumberAccepted() {
        FlightNumber flight = new FlightNumber("sw-104");

        assertThat(flight.value()).isEqualTo("SW-104");
        assertThat(flight.toString()).isEqualTo("SW-104");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SW-1", "BA-12", "LH-123", "AA-1234"})
    @DisplayName("valid flight number digits between 1 and 4 are accepted")
    void validFlightFormats(String flightCode) {
        FlightNumber flight = new FlightNumber(flightCode);
        assertThat(flight.value()).isEqualTo(flightCode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "SW", "SW104", "S-104", "SWW-104", "SW-12345", "12-104", "SW-ABC"})
    @DisplayName("invalid flight number formats throw IllegalArgumentException")
    void invalidFlightNumberRejected(String invalidFlight) {
        assertThatThrownBy(() -> new FlightNumber(invalidFlight))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Flight number must match format XX-1234");
    }

    @Test
    @DisplayName("null flight number throws NullPointerException")
    void nullFlightNumberThrowsException() {
        assertThatThrownBy(() -> new FlightNumber(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Flight number cannot be null");
    }
}
