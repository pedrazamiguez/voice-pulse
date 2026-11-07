package com.voicepulse.shared.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Airline flight number identifier (e.g. SW-104).
 *
 * <p>Validates standard IATA two-letter airline code followed by a hyphen and 1 to 4 numeric
 * digits.
 */
public record FlightNumber(String value) {

    private static final Pattern FLIGHT_PATTERN = Pattern.compile("^[A-Z]{2}-[0-9]{1,4}$");

    public FlightNumber {
        Objects.requireNonNull(value, "Flight number cannot be null");
        String normalized = value.trim().toUpperCase();
        if (!FLIGHT_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Flight number must match format XX-1234: " + value);
        }
        value = normalized;
    }

    @Override
    public String toString() {
        return value;
    }
}
