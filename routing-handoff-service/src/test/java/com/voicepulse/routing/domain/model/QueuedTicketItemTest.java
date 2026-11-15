package com.voicepulse.routing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.Map;
import org.instancio.Instancio;
import org.instancio.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QueuedTicketItem Domain Record Tests")
class QueuedTicketItemTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    private static final Model<TicketFormFilledEvent> TICKET_EVENT_MODEL =
            Instancio.of(TicketFormFilledEvent.class)
                    .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                    .set(
                            field(TicketFormFilledEvent::originalFlightNumber),
                            new FlightNumber("SW-104"))
                    .set(field(TicketFormFilledEvent::targetSkillGroup), "DISRUPTION_DESK_GOLD")
                    .set(field(TicketFormFilledEvent::payload), Map.of())
                    .toModel();

    @Test
    @DisplayName("Should construct valid QueuedTicketItem and delegate callId/ticketId")
    void shouldConstructValidQueuedTicketItem() {
        TicketFormFilledEvent event = Instancio.create(TICKET_EVENT_MODEL);

        QueuedTicketItem item =
                Instancio.of(QueuedTicketItem.class)
                        .set(field(QueuedTicketItem::ticketEvent), event)
                        .set(field(QueuedTicketItem::skillGroup), SkillGroup.DISRUPTION_DESK_GOLD)
                        .set(field(QueuedTicketItem::priorityScore), 100)
                        .set(field(QueuedTicketItem::enqueuedAt), NOW)
                        .create();

        assertThat(item.callId()).isEqualTo(event.callId());
        assertThat(item.ticketId()).isEqualTo(event.ticketId());
        assertThat(item.skillGroup()).isEqualTo(SkillGroup.DISRUPTION_DESK_GOLD);
        assertThat(item.priorityScore()).isEqualTo(100);
        assertThat(item.enqueuedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Should reject null required fields and negative priorityScore")
    void shouldRejectInvalidQueuedTicketItem() {
        TicketFormFilledEvent event = Instancio.create(TICKET_EVENT_MODEL);

        assertThatThrownBy(
                        () -> new QueuedTicketItem(null, SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ticketEvent cannot be null");

        assertThatThrownBy(() -> new QueuedTicketItem(event, null, 100, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("skillGroup cannot be null");

        assertThatThrownBy(
                        () ->
                                new QueuedTicketItem(
                                        event, SkillGroup.DISRUPTION_DESK_GOLD, 100, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("enqueuedAt cannot be null");

        assertThatThrownBy(
                        () -> new QueuedTicketItem(event, SkillGroup.DISRUPTION_DESK_GOLD, -1, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priorityScore cannot be negative");
    }
}
