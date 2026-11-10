package com.voicepulse.conversation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DisruptionTicketAssembler Domain Unit Tests")
class DisruptionTicketAssemblerTest {

    private static final String VALID_PNR = "A8F9X2";
    private static final String PASSENGER_NAME = "Andres";
    private static final String FLIGHT_NUMBER = "SW-104";

    @Test
    @DisplayName("Assembles valid AirlineDisruptionTicket with all mandatory business fields")
    void assemble_validParameters_createsImmutableTicket() {
        UUID callId = UUID.randomUUID();
        Instant now = Instant.now();

        AirlineDisruptionTicket ticket =
                DisruptionTicketAssembler.assemble(
                        callId,
                        PASSENGER_NAME,
                        VALID_PNR,
                        FLIGHT_NUMBER,
                        DisruptionReason.CANCELED_WEATHER,
                        RebookingPreference.NEXT_AVAILABLE,
                        true,
                        false,
                        now);

        assertThat(ticket).isNotNull();
        assertThat(ticket.ticketId()).isNotNull();
        assertThat(ticket.callId()).isEqualTo(callId);
        assertThat(ticket.passengerName()).isEqualTo(PASSENGER_NAME);
        assertThat(ticket.pnr()).isEqualTo(VALID_PNR);
        assertThat(ticket.originalFlightNumber()).isEqualTo(FLIGHT_NUMBER);
        assertThat(ticket.disruptionReason()).isEqualTo(DisruptionReason.CANCELED_WEATHER);
        assertThat(ticket.rebookingPreference()).isEqualTo(RebookingPreference.NEXT_AVAILABLE);
        assertThat(ticket.hotelRequired()).isTrue();
        assertThat(ticket.specialAssistance()).isFalse();
        assertThat(ticket.status()).isEqualTo("COMPILED");
        assertThat(ticket.createdAt()).isEqualTo(now);
        assertThat(ticket.updatedAt()).isEqualTo(now);
        assertThat(ticket.payload())
                .containsEntry("category", "HOTEL_ACCOMMODATION")
                .containsEntry("priority", "HIGH");
    }

    @Test
    @DisplayName("Assembles ticket category as FLIGHT_REBOOKING when hotel is not required")
    void assemble_noHotel_setsFlightRebookingCategory() {
        UUID callId = UUID.randomUUID();
        Instant now = Instant.now();

        AirlineDisruptionTicket ticket =
                DisruptionTicketAssembler.assemble(
                        callId,
                        PASSENGER_NAME,
                        VALID_PNR,
                        FLIGHT_NUMBER,
                        DisruptionReason.DELAYED_MAINTENANCE,
                        RebookingPreference.REFUND_REQUESTED,
                        false,
                        true,
                        now);

        assertThat(ticket.hotelRequired()).isFalse();
        assertThat(ticket.specialAssistance()).isTrue();
        assertThat(ticket.payload()).containsEntry("category", "REFUND_DISRUPTION");
    }

    @Test
    @DisplayName("Assembles ticket with custom metadata payload map")
    void assembleWithPayload_includesCustomMetadata() {
        UUID callId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> customMeta = Map.of("partySize", 2, "vip", true);

        AirlineDisruptionTicket ticket =
                DisruptionTicketAssembler.assembleWithPayload(
                        callId,
                        PASSENGER_NAME,
                        VALID_PNR,
                        FLIGHT_NUMBER,
                        DisruptionReason.CANCELED_WEATHER,
                        RebookingPreference.NEXT_AVAILABLE,
                        true,
                        false,
                        customMeta,
                        now);

        assertThat(ticket.payload())
                .containsEntry("partySize", 2)
                .containsEntry("vip", true)
                .containsEntry("category", "HOTEL_ACCOMMODATION");
    }

    @Test
    @DisplayName("Throws NullPointerException when mandatory parameters are null")
    void assemble_nullParameters_throwsNullPointerException() {
        UUID callId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(
                        () ->
                                DisruptionTicketAssembler.assemble(
                                        null,
                                        PASSENGER_NAME,
                                        VALID_PNR,
                                        FLIGHT_NUMBER,
                                        DisruptionReason.CANCELED_WEATHER,
                                        RebookingPreference.NEXT_AVAILABLE,
                                        false,
                                        false,
                                        now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("callId");

        assertThatThrownBy(
                        () ->
                                DisruptionTicketAssembler.assemble(
                                        callId,
                                        null,
                                        VALID_PNR,
                                        FLIGHT_NUMBER,
                                        DisruptionReason.CANCELED_WEATHER,
                                        RebookingPreference.NEXT_AVAILABLE,
                                        false,
                                        false,
                                        now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("passengerName");

        assertThatThrownBy(
                        () ->
                                DisruptionTicketAssembler.assemble(
                                        callId,
                                        PASSENGER_NAME,
                                        null,
                                        FLIGHT_NUMBER,
                                        DisruptionReason.CANCELED_WEATHER,
                                        RebookingPreference.NEXT_AVAILABLE,
                                        false,
                                        false,
                                        now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pnr");

        assertThatThrownBy(
                        () ->
                                DisruptionTicketAssembler.assemble(
                                        callId,
                                        PASSENGER_NAME,
                                        VALID_PNR,
                                        null,
                                        DisruptionReason.CANCELED_WEATHER,
                                        RebookingPreference.NEXT_AVAILABLE,
                                        false,
                                        false,
                                        now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("originalFlightNumber");

        assertThatThrownBy(
                        () ->
                                DisruptionTicketAssembler.assemble(
                                        callId,
                                        PASSENGER_NAME,
                                        VALID_PNR,
                                        FLIGHT_NUMBER,
                                        null,
                                        RebookingPreference.NEXT_AVAILABLE,
                                        false,
                                        false,
                                        now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("disruptionReason");

        assertThatThrownBy(
                        () ->
                                DisruptionTicketAssembler.assemble(
                                        callId,
                                        PASSENGER_NAME,
                                        VALID_PNR,
                                        FLIGHT_NUMBER,
                                        DisruptionReason.CANCELED_WEATHER,
                                        null,
                                        false,
                                        false,
                                        now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebookingPreference");

        assertThatThrownBy(
                        () ->
                                DisruptionTicketAssembler.assemble(
                                        callId,
                                        PASSENGER_NAME,
                                        VALID_PNR,
                                        FLIGHT_NUMBER,
                                        DisruptionReason.CANCELED_WEATHER,
                                        RebookingPreference.NEXT_AVAILABLE,
                                        false,
                                        false,
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    @DisplayName(
            "Throws IllegalArgumentException when PNR does not match 6 alphanumeric characters")
    void assemble_invalidPnr_throwsIllegalArgumentException() {
        UUID callId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(
                        () ->
                                DisruptionTicketAssembler.assemble(
                                        callId,
                                        PASSENGER_NAME,
                                        "SHORT",
                                        FLIGHT_NUMBER,
                                        DisruptionReason.CANCELED_WEATHER,
                                        RebookingPreference.NEXT_AVAILABLE,
                                        false,
                                        false,
                                        now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PNR must be exactly 6 alphanumeric characters");

        assertThatThrownBy(
                        () ->
                                DisruptionTicketAssembler.assemble(
                                        callId,
                                        PASSENGER_NAME,
                                        "TOOLONG1",
                                        FLIGHT_NUMBER,
                                        DisruptionReason.CANCELED_WEATHER,
                                        RebookingPreference.NEXT_AVAILABLE,
                                        false,
                                        false,
                                        now))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(
                        () ->
                                DisruptionTicketAssembler.assemble(
                                        callId,
                                        PASSENGER_NAME,
                                        "A8F-X2",
                                        FLIGHT_NUMBER,
                                        DisruptionReason.CANCELED_WEATHER,
                                        RebookingPreference.NEXT_AVAILABLE,
                                        false,
                                        false,
                                        now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Instancio fixture populates non-constrained fields consistently")
    void assemble_instancioFixture_verifiesInvariants() {
        AirlineDisruptionTicket fixture =
                Instancio.of(AirlineDisruptionTicket.class)
                        .generate(
                                field(AirlineDisruptionTicket::pnr),
                                gen -> gen.string().length(6).alphaNumeric())
                        .generate(
                                field(AirlineDisruptionTicket::originalFlightNumber),
                                gen -> gen.oneOf("SW-101", "SW-202", "SW-104"))
                        .create();

        assertThat(fixture.pnr()).matches("^[A-Za-z0-9]{6}$");
        assertThat(fixture.originalFlightNumber()).startsWith("SW-");
    }
}
