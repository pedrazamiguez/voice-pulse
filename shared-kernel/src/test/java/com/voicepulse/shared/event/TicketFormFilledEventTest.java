package com.voicepulse.shared.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.util.HashMap;
import java.util.Map;
import org.instancio.Instancio;
import org.instancio.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TicketFormFilledEvent Domain Envelope Tests")
class TicketFormFilledEventTest {

    private static final Model<TicketFormFilledEvent> VALID_EVENT_MODEL =
            Instancio.of(TicketFormFilledEvent.class)
                    .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                    .set(
                            field(TicketFormFilledEvent::originalFlightNumber),
                            new FlightNumber("SW-104"))
                    .set(
                            field(TicketFormFilledEvent::payload),
                            Map.of("origin", "CTU", "destination", "MAD"))
                    .toModel();

    private static TicketFormFilledEvent createValidEvent() {
        return Instancio.create(VALID_EVENT_MODEL);
    }

    @Test
    @DisplayName("Should create valid TicketFormFilledEvent with defensive payload copy")
    void shouldCreateValidTicketFormFilledEvent() {
        Map<String, Object> mutablePayload = new HashMap<>();
        mutablePayload.put("party_size", 2);

        TicketFormFilledEvent event =
                Instancio.of(VALID_EVENT_MODEL)
                        .set(field(TicketFormFilledEvent::payload), mutablePayload)
                        .create();

        mutablePayload.put("mutated", true);

        assertThat(event.callId()).isNotNull();
        assertThat(event.ticketId()).isNotNull();
        assertThat(event.pnr()).isEqualTo(new Pnr("A8F9X2"));
        assertThat(event.originalFlightNumber()).isEqualTo(new FlightNumber("SW-104"));
        assertThat(event.payload()).containsOnlyKeys("party_size");
        assertThatThrownBy(() -> event.payload().put("illegal", "mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should default null payload to empty immutable map")
    void shouldDefaultNullPayloadToEmptyMap() {
        TicketFormFilledEvent b = createValidEvent();
        TicketFormFilledEvent event =
                new TicketFormFilledEvent(
                        b.callId(),
                        b.ticketId(),
                        b.passengerName(),
                        b.pnr(),
                        b.originalFlightNumber(),
                        b.disruptionReason(),
                        b.rebookingPreference(),
                        b.hotelRequired(),
                        b.specialAssistance(),
                        b.targetSkillGroup(),
                        null,
                        b.timestamp());

        assertThat(event.payload()).isEmpty();
    }

    @Test
    @DisplayName("Should reject null required fields")
    void shouldRejectNullRequiredFields() {
        TicketFormFilledEvent b = createValidEvent();

        assertThatThrownBy(
                        () ->
                                new TicketFormFilledEvent(
                                        null,
                                        b.ticketId(),
                                        b.passengerName(),
                                        b.pnr(),
                                        b.originalFlightNumber(),
                                        b.disruptionReason(),
                                        b.rebookingPreference(),
                                        b.hotelRequired(),
                                        b.specialAssistance(),
                                        b.targetSkillGroup(),
                                        b.payload(),
                                        b.timestamp()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("callId must not be null");

        assertThatThrownBy(
                        () ->
                                new TicketFormFilledEvent(
                                        b.callId(),
                                        null,
                                        b.passengerName(),
                                        b.pnr(),
                                        b.originalFlightNumber(),
                                        b.disruptionReason(),
                                        b.rebookingPreference(),
                                        b.hotelRequired(),
                                        b.specialAssistance(),
                                        b.targetSkillGroup(),
                                        b.payload(),
                                        b.timestamp()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ticketId must not be null");

        assertThatThrownBy(
                        () ->
                                new TicketFormFilledEvent(
                                        b.callId(),
                                        b.ticketId(),
                                        b.passengerName(),
                                        null,
                                        b.originalFlightNumber(),
                                        b.disruptionReason(),
                                        b.rebookingPreference(),
                                        b.hotelRequired(),
                                        b.specialAssistance(),
                                        b.targetSkillGroup(),
                                        b.payload(),
                                        b.timestamp()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("pnr must not be null");

        assertThatThrownBy(
                        () ->
                                new TicketFormFilledEvent(
                                        b.callId(),
                                        b.ticketId(),
                                        b.passengerName(),
                                        b.pnr(),
                                        null,
                                        b.disruptionReason(),
                                        b.rebookingPreference(),
                                        b.hotelRequired(),
                                        b.specialAssistance(),
                                        b.targetSkillGroup(),
                                        b.payload(),
                                        b.timestamp()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("originalFlightNumber must not be null");

        assertThatThrownBy(
                        () ->
                                new TicketFormFilledEvent(
                                        b.callId(),
                                        b.ticketId(),
                                        b.passengerName(),
                                        b.pnr(),
                                        b.originalFlightNumber(),
                                        b.disruptionReason(),
                                        b.rebookingPreference(),
                                        b.hotelRequired(),
                                        b.specialAssistance(),
                                        b.targetSkillGroup(),
                                        b.payload(),
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("timestamp must not be null");
    }

    @Test
    @DisplayName("Should reject blank passengerName or targetSkillGroup")
    void shouldRejectBlankStrings() {
        TicketFormFilledEvent b = createValidEvent();

        assertThatThrownBy(
                        () ->
                                new TicketFormFilledEvent(
                                        b.callId(),
                                        b.ticketId(),
                                        "   ",
                                        b.pnr(),
                                        b.originalFlightNumber(),
                                        b.disruptionReason(),
                                        b.rebookingPreference(),
                                        b.hotelRequired(),
                                        b.specialAssistance(),
                                        b.targetSkillGroup(),
                                        b.payload(),
                                        b.timestamp()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("passengerName must not be blank");

        assertThatThrownBy(
                        () ->
                                new TicketFormFilledEvent(
                                        b.callId(),
                                        b.ticketId(),
                                        b.passengerName(),
                                        b.pnr(),
                                        b.originalFlightNumber(),
                                        b.disruptionReason(),
                                        b.rebookingPreference(),
                                        b.hotelRequired(),
                                        b.specialAssistance(),
                                        "  ",
                                        b.payload(),
                                        b.timestamp()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("targetSkillGroup must not be blank");
    }
}
