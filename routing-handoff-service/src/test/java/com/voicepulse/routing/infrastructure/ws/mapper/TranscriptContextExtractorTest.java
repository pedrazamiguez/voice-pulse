package com.voicepulse.routing.infrastructure.ws.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.ws.dto.TranscriptTurnDto;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.instancio.Instancio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TranscriptContextExtractor Tests")
class TranscriptContextExtractorTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-11-15T08:15:00Z");
    private static final Instant TURN_TIME = Instant.parse("2026-11-15T08:14:12Z");

    private final TranscriptContextExtractor extractor = new TranscriptContextExtractor();

    private static TicketFormFilledEvent ticketWithPayload(Map<String, Object> payload) {
        return Instancio.of(TicketFormFilledEvent.class)
                .set(field(TicketFormFilledEvent::passengerName), "Jane Doe")
                .set(field(TicketFormFilledEvent::pnr), new Pnr("AB7DF9"))
                .set(field(TicketFormFilledEvent::originalFlightNumber), new FlightNumber("SW-104"))
                .set(
                        field(TicketFormFilledEvent::disruptionReason),
                        DisruptionReason.CANCELED_WEATHER)
                .set(
                        field(TicketFormFilledEvent::rebookingPreference),
                        RebookingPreference.NEXT_AVAILABLE)
                .set(field(TicketFormFilledEvent::hotelRequired), false)
                .set(field(TicketFormFilledEvent::specialAssistance), false)
                .set(
                        field(TicketFormFilledEvent::targetSkillGroup),
                        SkillGroup.DISRUPTION_DESK_STANDARD.name())
                .set(field(TicketFormFilledEvent::payload), payload)
                .set(field(TicketFormFilledEvent::timestamp), EVENT_TIME)
                .create();
    }

    @Test
    @DisplayName("Extracts structured transcript turns, summary, priority, category, and caller")
    void extractsStructuredPayloadContext() {
        TicketFormFilledEvent event =
                ticketWithPayload(
                        Map.of(
                                "summary", "Canceled flight SW-104; needs hotel and next flight.",
                                "priority", "urgent",
                                "category", "rebooking",
                                "caller_number", "+34 600 123 456",
                                "transcript",
                                        List.of(
                                                Map.of(
                                                        "speaker", "caller",
                                                        "text", "My flight SW-104 was canceled.",
                                                        "timestamp", TURN_TIME.toString()),
                                                Map.of(
                                                        "role",
                                                        "agent",
                                                        "content",
                                                        "Confirming"
                                                            + " Alpha-Bravo-7-Delta-Foxtrot-9."),
                                                "CALLER: Please get me a hotel too.")));

        assertThat(extractor.extractSummary(event))
                .isEqualTo("Canceled flight SW-104; needs hotel and next flight.");
        assertThat(extractor.extractPriority(event, SkillGroup.DISRUPTION_DESK_STANDARD))
                .isEqualTo("URGENT");
        assertThat(extractor.extractCategory(event)).isEqualTo("REBOOKING");
        assertThat(extractor.extractCallerNumber(event)).isEqualTo("+34 600 123 456");
        assertThat(extractor.extractTranscript(event))
                .containsExactly(
                        new TranscriptTurnDto(
                                "CALLER", "My flight SW-104 was canceled.", TURN_TIME),
                        new TranscriptTurnDto(
                                "AGENT", "Confirming Alpha-Bravo-7-Delta-Foxtrot-9.", EVENT_TIME),
                        new TranscriptTurnDto("CALLER", "Please get me a hotel too.", EVENT_TIME));
        assertThat(extractor.extractMetadata(event)).containsKey("caller_number");
    }

    @Test
    @DisplayName("Parses newline-delimited transcript text and derives defaults when absent")
    void parsesStringTranscriptAndDerivesFallbacks() {
        TicketFormFilledEvent event =
                ticketWithPayload(
                        Map.of(
                                "callerNumber", "+1-555-0199",
                                "turns",
                                        "CALLER: Hello!\nAGENT: How can I help?\nPlain utterance"));

        assertThat(extractor.extractSummary(event))
                .contains("Flight SW-104 disrupted (CANCELED_WEATHER)")
                .contains("Jane Doe");
        assertThat(extractor.extractPriority(event, SkillGroup.DISRUPTION_DESK_GOLD))
                .isEqualTo("HIGH");
        assertThat(extractor.extractPriority(event, SkillGroup.DISRUPTION_DESK_STANDARD))
                .isEqualTo("NORMAL");
        assertThat(extractor.extractCategory(event)).isEqualTo("NEXT_AVAILABLE");
        assertThat(extractor.extractCallerNumber(event)).isEqualTo("+1-555-0199");
        assertThat(extractor.extractTranscript(event))
                .containsExactly(
                        new TranscriptTurnDto("CALLER", "Hello!", EVENT_TIME),
                        new TranscriptTurnDto("AGENT", "How can I help?", EVENT_TIME),
                        new TranscriptTurnDto("CALLER", "Plain utterance", EVENT_TIME));
    }

    @Test
    @DisplayName("Returns empty transcript and metadata cleanly when payload is empty")
    void handlesEmptyPayloadSafely() {
        TicketFormFilledEvent event = ticketWithPayload(Map.of());

        assertThat(extractor.extractTranscript(event)).isEmpty();
        assertThat(extractor.extractMetadata(event)).isEmpty();
        assertThat(extractor.extractCallerNumber(event)).isNull();
    }
}
