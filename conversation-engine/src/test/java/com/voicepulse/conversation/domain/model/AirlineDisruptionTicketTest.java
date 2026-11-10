package com.voicepulse.conversation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.instancio.Instancio;
import org.instancio.InstancioApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AirlineDisruptionTicket Domain Model Tests")
class AirlineDisruptionTicketTest {

    private static InstancioApi<AirlineDisruptionTicket> validTicketSpec() {
        return Instancio.of(AirlineDisruptionTicket.class)
                .generate(
                        field(AirlineDisruptionTicket::pnr),
                        gen -> gen.string().length(6).alphaNumeric());
    }

    private static AirlineDisruptionTicket createValidTicket() {
        return validTicketSpec().create();
    }

    @Nested
    @DisplayName("Creation & Happy Path")
    class CreationTests {

        @Test
        @DisplayName("Should create valid AirlineDisruptionTicket with all attributes")
        void shouldCreateValidTicket() {
            UUID ticketId = UUID.randomUUID();
            UUID callId = UUID.randomUUID();
            Instant now = Instant.now();
            Map<String, Object> payload =
                    Map.of("departureAirport", "CTU", "arrivalAirport", "MAD");

            AirlineDisruptionTicket ticket =
                    validTicketSpec()
                            .set(field(AirlineDisruptionTicket::ticketId), ticketId)
                            .set(field(AirlineDisruptionTicket::callId), callId)
                            .set(
                                    field(AirlineDisruptionTicket::passengerName),
                                    "Andrés Pedraza Míguez")
                            .set(field(AirlineDisruptionTicket::pnr), "A8F9X2")
                            .set(field(AirlineDisruptionTicket::originalFlightNumber), "SW-104")
                            .set(
                                    field(AirlineDisruptionTicket::disruptionReason),
                                    DisruptionReason.CANCELED_WEATHER)
                            .set(
                                    field(AirlineDisruptionTicket::rebookingPreference),
                                    RebookingPreference.NEXT_AVAILABLE)
                            .set(field(AirlineDisruptionTicket::hotelRequired), true)
                            .set(field(AirlineDisruptionTicket::specialAssistance), false)
                            .set(field(AirlineDisruptionTicket::status), "PENDING_HANDOFF")
                            .set(field(AirlineDisruptionTicket::payload), payload)
                            .set(field(AirlineDisruptionTicket::createdAt), now)
                            .set(field(AirlineDisruptionTicket::updatedAt), now)
                            .create();

            assertThat(ticket.ticketId()).isEqualTo(ticketId);
            assertThat(ticket.callId()).isEqualTo(callId);
            assertThat(ticket.passengerName()).isEqualTo("Andrés Pedraza Míguez");
            assertThat(ticket.pnr()).isEqualTo("A8F9X2");
            assertThat(ticket.originalFlightNumber()).isEqualTo("SW-104");
            assertThat(ticket.disruptionReason()).isEqualTo(DisruptionReason.CANCELED_WEATHER);
            assertThat(ticket.rebookingPreference()).isEqualTo(RebookingPreference.NEXT_AVAILABLE);
            assertThat(ticket.hotelRequired()).isTrue();
            assertThat(ticket.specialAssistance()).isFalse();
            assertThat(ticket.status()).isEqualTo("PENDING_HANDOFF");
            assertThat(ticket.payload()).containsEntry("departureAirport", "CTU");
            assertThat(ticket.createdAt()).isEqualTo(now);
            assertThat(ticket.updatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("Should convert null payload to empty immutable map")
        void shouldConvertNullPayloadToEmptyMap() {
            AirlineDisruptionTicket ticket =
                    validTicketSpec().set(field(AirlineDisruptionTicket::payload), null).create();

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
                    validTicketSpec()
                            .set(field(AirlineDisruptionTicket::payload), mutableMap)
                            .create();

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
                    validTicketSpec().set(field(AirlineDisruptionTicket::pnr), validPnr).create();

            assertThat(ticket.pnr()).isEqualTo(validPnr);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "SHORT", "TOOLONG7", "AB-123", "A8F9X!", " "})
        @DisplayName("Should reject invalid PNR formats")
        void shouldRejectInvalidPnrs(String invalidPnr) {
            AirlineDisruptionTicket base = createValidTicket();
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            base.ticketId(),
                                            base.callId(),
                                            base.passengerName(),
                                            invalidPnr,
                                            base.originalFlightNumber(),
                                            base.disruptionReason(),
                                            base.rebookingPreference(),
                                            base.hotelRequired(),
                                            base.specialAssistance(),
                                            base.status(),
                                            base.payload(),
                                            base.createdAt(),
                                            base.updatedAt()))
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
            AirlineDisruptionTicket b = createValidTicket();
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            null,
                                            b.callId(),
                                            b.passengerName(),
                                            b.pnr(),
                                            b.originalFlightNumber(),
                                            b.disruptionReason(),
                                            b.rebookingPreference(),
                                            b.hotelRequired(),
                                            b.specialAssistance(),
                                            b.status(),
                                            b.payload(),
                                            b.createdAt(),
                                            b.updatedAt()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ticketId must not be null");
        }

        @Test
        @DisplayName("Should reject null callId")
        void shouldRejectNullCallId() {
            AirlineDisruptionTicket b = createValidTicket();
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            b.ticketId(),
                                            null,
                                            b.passengerName(),
                                            b.pnr(),
                                            b.originalFlightNumber(),
                                            b.disruptionReason(),
                                            b.rebookingPreference(),
                                            b.hotelRequired(),
                                            b.specialAssistance(),
                                            b.status(),
                                            b.payload(),
                                            b.createdAt(),
                                            b.updatedAt()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("callId must not be null");
        }

        @Test
        @DisplayName("Should reject null passengerName")
        void shouldRejectNullPassengerName() {
            AirlineDisruptionTicket b = createValidTicket();
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            b.ticketId(),
                                            b.callId(),
                                            null,
                                            b.pnr(),
                                            b.originalFlightNumber(),
                                            b.disruptionReason(),
                                            b.rebookingPreference(),
                                            b.hotelRequired(),
                                            b.specialAssistance(),
                                            b.status(),
                                            b.payload(),
                                            b.createdAt(),
                                            b.updatedAt()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("passengerName must not be null");
        }

        @Test
        @DisplayName("Should reject null pnr")
        void shouldRejectNullPnr() {
            AirlineDisruptionTicket b = createValidTicket();
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            b.ticketId(),
                                            b.callId(),
                                            b.passengerName(),
                                            null,
                                            b.originalFlightNumber(),
                                            b.disruptionReason(),
                                            b.rebookingPreference(),
                                            b.hotelRequired(),
                                            b.specialAssistance(),
                                            b.status(),
                                            b.payload(),
                                            b.createdAt(),
                                            b.updatedAt()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("pnr must not be null");
        }

        @Test
        @DisplayName("Should reject null originalFlightNumber")
        void shouldRejectNullFlightNumber() {
            AirlineDisruptionTicket b = createValidTicket();
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            b.ticketId(),
                                            b.callId(),
                                            b.passengerName(),
                                            b.pnr(),
                                            null,
                                            b.disruptionReason(),
                                            b.rebookingPreference(),
                                            b.hotelRequired(),
                                            b.specialAssistance(),
                                            b.status(),
                                            b.payload(),
                                            b.createdAt(),
                                            b.updatedAt()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("originalFlightNumber must not be null");
        }

        @Test
        @DisplayName("Should reject null disruptionReason")
        void shouldRejectNullReason() {
            AirlineDisruptionTicket b = createValidTicket();
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            b.ticketId(),
                                            b.callId(),
                                            b.passengerName(),
                                            b.pnr(),
                                            b.originalFlightNumber(),
                                            null,
                                            b.rebookingPreference(),
                                            b.hotelRequired(),
                                            b.specialAssistance(),
                                            b.status(),
                                            b.payload(),
                                            b.createdAt(),
                                            b.updatedAt()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("disruptionReason must not be null");
        }

        @Test
        @DisplayName("Should reject null rebookingPreference")
        void shouldRejectNullPreference() {
            AirlineDisruptionTicket b = createValidTicket();
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            b.ticketId(),
                                            b.callId(),
                                            b.passengerName(),
                                            b.pnr(),
                                            b.originalFlightNumber(),
                                            b.disruptionReason(),
                                            null,
                                            b.hotelRequired(),
                                            b.specialAssistance(),
                                            b.status(),
                                            b.payload(),
                                            b.createdAt(),
                                            b.updatedAt()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("rebookingPreference must not be null");
        }

        @Test
        @DisplayName("Should reject null status")
        void shouldRejectNullStatus() {
            AirlineDisruptionTicket b = createValidTicket();
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            b.ticketId(),
                                            b.callId(),
                                            b.passengerName(),
                                            b.pnr(),
                                            b.originalFlightNumber(),
                                            b.disruptionReason(),
                                            b.rebookingPreference(),
                                            b.hotelRequired(),
                                            b.specialAssistance(),
                                            null,
                                            b.payload(),
                                            b.createdAt(),
                                            b.updatedAt()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("status must not be null");
        }

        @Test
        @DisplayName("Should reject null createdAt")
        void shouldRejectNullCreatedAt() {
            AirlineDisruptionTicket b = createValidTicket();
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            b.ticketId(),
                                            b.callId(),
                                            b.passengerName(),
                                            b.pnr(),
                                            b.originalFlightNumber(),
                                            b.disruptionReason(),
                                            b.rebookingPreference(),
                                            b.hotelRequired(),
                                            b.specialAssistance(),
                                            b.status(),
                                            b.payload(),
                                            null,
                                            b.updatedAt()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("createdAt must not be null");
        }

        @Test
        @DisplayName("Should reject null updatedAt")
        void shouldRejectNullUpdatedAt() {
            AirlineDisruptionTicket b = createValidTicket();
            assertThatThrownBy(
                            () ->
                                    new AirlineDisruptionTicket(
                                            b.ticketId(),
                                            b.callId(),
                                            b.passengerName(),
                                            b.pnr(),
                                            b.originalFlightNumber(),
                                            b.disruptionReason(),
                                            b.rebookingPreference(),
                                            b.hotelRequired(),
                                            b.specialAssistance(),
                                            b.status(),
                                            b.payload(),
                                            b.createdAt(),
                                            null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("updatedAt must not be null");
        }
    }
}
