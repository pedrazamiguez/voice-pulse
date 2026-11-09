package com.voicepulse.conversation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AirlineDisruptionTicket Domain Model Tests")
class AirlineDisruptionTicketTest {

    private static final UUID TICKET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CALL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PASSENGER = "Andrés Pedraza Míguez";
    private static final String PNR = "A8F9X2";
    private static final String FLIGHT = "SW-104";
    private static final DisruptionReason REASON = DisruptionReason.CANCELED_WEATHER;
    private static final RebookingPreference PREFERENCE = RebookingPreference.NEXT_AVAILABLE;
    private static final String STATUS = "PENDING_HANDOFF";
    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");

    @Nested
    @DisplayName("Creation & Happy Path")
    class CreationTests {

        @Test
        @DisplayName("Should create valid AirlineDisruptionTicket with all attributes")
        void shouldCreateValidTicket() {
            Map<String, Object> payload =
                    Map.of("departureAirport", "CTU", "arrivalAirport", "MAD");

            AirlineDisruptionTicket ticket =
                    new AirlineDisruptionTicket(
                            TICKET_ID,
                            CALL_ID,
                            PASSENGER,
                            PNR,
                            FLIGHT,
                            REASON,
                            PREFERENCE,
                            true,
                            false,
                            STATUS,
                            payload,
                            NOW,
                            NOW);

            assertThat(ticket.ticketId()).isEqualTo(TICKET_ID);
            assertThat(ticket.callId()).isEqualTo(CALL_ID);
            assertThat(ticket.passengerName()).isEqualTo(PASSENGER);
            assertThat(ticket.pnr()).isEqualTo(PNR);
            assertThat(ticket.originalFlightNumber()).isEqualTo(FLIGHT);
            assertThat(ticket.disruptionReason()).isEqualTo(REASON);
            assertThat(ticket.rebookingPreference()).isEqualTo(PREFERENCE);
            assertThat(ticket.hotelRequired()).isTrue();
            assertThat(ticket.specialAssistance()).isFalse();
            assertThat(ticket.status()).isEqualTo(STATUS);
            assertThat(ticket.payload()).containsEntry("departureAirport", "CTU");
            assertThat(ticket.createdAt()).isEqualTo(NOW);
            assertThat(ticket.updatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("Should convert null payload to empty immutable map")
        void shouldConvertNullPayloadToEmptyMap() {
            AirlineDisruptionTicket ticket =
                    new AirlineDisruptionTicket(
                            TICKET_ID,
                            CALL_ID,
                            PASSENGER,
                            PNR,
                            FLIGHT,
                            REASON,
                            PREFERENCE,
                            false,
                            false,
                            STATUS,
                            null,
                            NOW,
                            NOW);

            assertThat(ticket.payload()).isNotNull().isEmpty();
            assertThatThrownBy(() -> ticket.payload().put("key", "val"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Should defensively copy payload map to preserve immutability")
        void shouldDefensivelyCopyPayload() {
            Map<String, Object> mutableMap = new HashMap<>();
            mutableMap.put("gate", "B12");

            AirlineDisruptionTicket ticket =
                    new AirlineDisruptionTicket(
                            TICKET_ID,
                            CALL_ID,
                            PASSENGER,
                            PNR,
                            FLIGHT,
                            REASON,
                            PREFERENCE,
                            false,
                            false,
                            STATUS,
                            mutableMap,
                            NOW,
                            NOW);

            mutableMap.put("gate", "C15");
            assertThat(ticket.payload().get("gate")).isEqualTo("B12");
        }
    }

    @Nested
    @DisplayName("PNR Validation")
    class PnrValidationTests {

        @ParameterizedTest
        @ValueSource(strings = {"A8F9X2", "ABCDEF", "123456", "ZZZZZZ", "000000"})
        @DisplayName("Should accept valid 6-character alphanumeric PNRs")
        void shouldAcceptValidPnrs(String validPnr) {
            AirlineDisruptionTicket ticket =
                    new AirlineDisruptionTicket(
                            TICKET_ID,
                            CALL_ID,
                            PASSENGER,
                            validPnr,
                            FLIGHT,
                            REASON,
                            PREFERENCE,
                            false,
                            false,
                            STATUS,
                            Map.of(),
                            NOW,
                            NOW);

            assertThat(ticket.pnr()).isEqualTo(validPnr);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "SHORT", "TOOLONG7", "AB-123", "A8F9X!", " "})
        @DisplayName("Should reject invalid PNR formats")
        void shouldRejectInvalidPnrs(String invalidPnr) {
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            TICKET_ID,
                                            CALL_ID,
                                            PASSENGER,
                                            invalidPnr,
                                            FLIGHT,
                                            REASON,
                                            PREFERENCE,
                                            false,
                                            false,
                                            STATUS,
                                            Map.of(),
                                            NOW,
                                            NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PNR must be exactly 6 alphanumeric characters");
        }
    }

    @Nested
    @DisplayName("Null Field Validation")
    class NullFieldTests {

        @Test
        @DisplayName("Should reject null ticketId")
        void shouldRejectNullTicketId() {
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            null,
                                            CALL_ID,
                                            PASSENGER,
                                            PNR,
                                            FLIGHT,
                                            REASON,
                                            PREFERENCE,
                                            false,
                                            false,
                                            STATUS,
                                            Map.of(),
                                            NOW,
                                            NOW))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ticketId must not be null");
        }

        @Test
        @DisplayName("Should reject null callId")
        void shouldRejectNullCallId() {
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            TICKET_ID,
                                            null,
                                            PASSENGER,
                                            PNR,
                                            FLIGHT,
                                            REASON,
                                            PREFERENCE,
                                            false,
                                            false,
                                            STATUS,
                                            Map.of(),
                                            NOW,
                                            NOW))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("callId must not be null");
        }

        @Test
        @DisplayName("Should reject null passengerName")
        void shouldRejectNullPassengerName() {
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            TICKET_ID,
                                            CALL_ID,
                                            null,
                                            PNR,
                                            FLIGHT,
                                            REASON,
                                            PREFERENCE,
                                            false,
                                            false,
                                            STATUS,
                                            Map.of(),
                                            NOW,
                                            NOW))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("passengerName must not be null");
        }

        @Test
        @DisplayName("Should reject null pnr")
        void shouldRejectNullPnr() {
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            TICKET_ID,
                                            CALL_ID,
                                            PASSENGER,
                                            null,
                                            FLIGHT,
                                            REASON,
                                            PREFERENCE,
                                            false,
                                            false,
                                            STATUS,
                                            Map.of(),
                                            NOW,
                                            NOW))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("pnr must not be null");
        }

        @Test
        @DisplayName("Should reject null originalFlightNumber")
        void shouldRejectNullFlightNumber() {
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            TICKET_ID,
                                            CALL_ID,
                                            PASSENGER,
                                            PNR,
                                            null,
                                            REASON,
                                            PREFERENCE,
                                            false,
                                            false,
                                            STATUS,
                                            Map.of(),
                                            NOW,
                                            NOW))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("originalFlightNumber must not be null");
        }

        @Test
        @DisplayName("Should reject null disruptionReason")
        void shouldRejectNullReason() {
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            TICKET_ID,
                                            CALL_ID,
                                            PASSENGER,
                                            PNR,
                                            FLIGHT,
                                            null,
                                            PREFERENCE,
                                            false,
                                            false,
                                            STATUS,
                                            Map.of(),
                                            NOW,
                                            NOW))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("disruptionReason must not be null");
        }

        @Test
        @DisplayName("Should reject null rebookingPreference")
        void shouldRejectNullPreference() {
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            TICKET_ID, CALL_ID, PASSENGER, PNR, FLIGHT, REASON,
                                            null, false, false, STATUS, Map.of(), NOW, NOW))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("rebookingPreference must not be null");
        }

        @Test
        @DisplayName("Should reject null status")
        void shouldRejectNullStatus() {
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            TICKET_ID,
                                            CALL_ID,
                                            PASSENGER,
                                            PNR,
                                            FLIGHT,
                                            REASON,
                                            PREFERENCE,
                                            false,
                                            false,
                                            null,
                                            Map.of(),
                                            NOW,
                                            NOW))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("status must not be null");
        }

        @Test
        @DisplayName("Should reject null createdAt")
        void shouldRejectNullCreatedAt() {
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            TICKET_ID,
                                            CALL_ID,
                                            PASSENGER,
                                            PNR,
                                            FLIGHT,
                                            REASON,
                                            PREFERENCE,
                                            false,
                                            false,
                                            STATUS,
                                            Map.of(),
                                            null,
                                            NOW))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("createdAt must not be null");
        }

        @Test
        @DisplayName("Should reject null updatedAt")
        void shouldRejectNullUpdatedAt() {
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            TICKET_ID,
                                            CALL_ID,
                                            PASSENGER,
                                            PNR,
                                            FLIGHT,
                                            REASON,
                                            PREFERENCE,
                                            false,
                                            false,
                                            STATUS,
                                            Map.of(),
                                            NOW,
                                            null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("updatedAt must not be null");
        }
    }
}
