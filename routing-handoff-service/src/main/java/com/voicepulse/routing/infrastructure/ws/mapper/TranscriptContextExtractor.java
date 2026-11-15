package com.voicepulse.routing.infrastructure.ws.mapper;

import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.ws.dto.TranscriptTurnDto;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Extracts structured transcript turns, priority, category, summary, and caller metadata from
 * {@link TicketFormFilledEvent} payloads without runtime reflection.
 */
@Component
public class TranscriptContextExtractor {

    private static final String ERR_EVENT_NULL = "event cannot be null";

    private static final List<String> TRANSCRIPT_KEYS =
            List.of("transcript", "transcriptTurns", "conversationTranscript", "turns");

    /** Extracts or synthesizes a concise human-readable summary of the disruption ticket. */
    public String extractSummary(TicketFormFilledEvent event) {
        Objects.requireNonNull(event, ERR_EVENT_NULL);
        Object rawSummary = event.payload().get("summary");
        if (rawSummary instanceof String summary && !summary.isBlank()) {
            return summary.trim();
        }
        return String.format(
                Locale.ROOT,
                "Flight %s disrupted (%s). Passenger %s requests %s (hotel=%s, assistance=%s).",
                event.originalFlightNumber(),
                event.disruptionReason(),
                event.passengerName(),
                event.rebookingPreference(),
                event.hotelRequired(),
                event.specialAssistance());
    }

    /** Extracts explicit priority from payload or derives priority from ticket flags and skill. */
    public String extractPriority(TicketFormFilledEvent event, SkillGroup resolvedSkillGroup) {
        Objects.requireNonNull(event, ERR_EVENT_NULL);
        Object rawPriority = event.payload().get("priority");
        if (rawPriority instanceof String priority && !priority.isBlank()) {
            return priority.trim().toUpperCase(Locale.ROOT);
        }
        boolean prioritySkill =
                resolvedSkillGroup == SkillGroup.DISRUPTION_DESK_GOLD
                        || resolvedSkillGroup == SkillGroup.DISRUPTION_DESK_ACCESSIBILITY
                        || resolvedSkillGroup == SkillGroup.DISRUPTION_DESK_PRIORITY;
        return (event.hotelRequired() || event.specialAssistance() || prioritySkill)
                ? "HIGH"
                : "NORMAL";
    }

    /** Extracts disruption category from payload or derives a deterministic default category. */
    public String extractCategory(TicketFormFilledEvent event) {
        Objects.requireNonNull(event, ERR_EVENT_NULL);
        Object rawCategory = event.payload().get("category");
        if (rawCategory instanceof String category && !category.isBlank()) {
            return category.trim().toUpperCase(Locale.ROOT);
        }
        return event.hotelRequired()
                ? "ACCOMMODATION_AND_REBOOKING"
                : event.rebookingPreference().name();
    }

    /** Extracts caller telephone number if supplied in payload metadata. */
    public String extractCallerNumber(TicketFormFilledEvent event) {
        Objects.requireNonNull(event, ERR_EVENT_NULL);
        Map<String, Object> payload = event.payload();
        for (String key : List.of("caller_number", "callerNumber")) {
            Object rawValue = payload.get(key);
            if (rawValue instanceof String number && !number.isBlank()) {
                return number.trim();
            }
        }
        return null;
    }

    /**
     * Extracts ordered conversational transcript turns from structured list or newline-delimited
     * text fields inside {@link TicketFormFilledEvent#payload()}.
     */
    public List<TranscriptTurnDto> extractTranscript(TicketFormFilledEvent event) {
        Objects.requireNonNull(event, ERR_EVENT_NULL);
        Map<String, Object> payload = event.payload();
        Object rawTranscript = findTranscriptValue(payload);
        if (rawTranscript == null) {
            return List.of();
        }
        Instant fallbackTime = event.timestamp();
        if (rawTranscript instanceof List<?> entries) {
            return parseTurnList(entries, fallbackTime);
        }
        if (rawTranscript instanceof String textBlock && !textBlock.isBlank()) {
            return parseTextLines(textBlock, fallbackTime);
        }
        return List.of();
    }

    /** Returns an immutable copy of non-null payload metadata entries. */
    public Map<String, Object> extractMetadata(TicketFormFilledEvent event) {
        Objects.requireNonNull(event, ERR_EVENT_NULL);
        Map<String, Object> payload = event.payload();
        if (payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object findTranscriptValue(Map<String, Object> payload) {
        for (String key : TRANSCRIPT_KEYS) {
            Object value = payload.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static List<TranscriptTurnDto> parseTurnList(List<?> entries, Instant fallbackTime) {
        List<TranscriptTurnDto> turns = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> map) {
                TranscriptTurnDto parsed = parseMapTurn(map, fallbackTime);
                if (parsed != null) {
                    turns.add(parsed);
                }
            } else if (entry instanceof String line && !line.isBlank()) {
                turns.add(parseLineTurn(line, fallbackTime));
            }
        }
        return List.copyOf(turns);
    }

    private static List<TranscriptTurnDto> parseTextLines(String textBlock, Instant fallbackTime) {
        String[] lines = textBlock.split("\\R");
        List<TranscriptTurnDto> turns = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                turns.add(parseLineTurn(line, fallbackTime));
            }
        }
        return List.copyOf(turns);
    }

    private static TranscriptTurnDto parseMapTurn(Map<?, ?> map, Instant fallbackTime) {
        String text = firstNonBlankString(map, List.of("text", "content", "message", "utterance"));
        if (text == null) {
            return null;
        }
        String speaker = firstNonBlankString(map, List.of("speaker", "role", "author"));
        String normalizedSpeaker = speaker == null ? "CALLER" : speaker.toUpperCase(Locale.ROOT);
        Instant timestamp = extractTimestamp(map, fallbackTime);
        return new TranscriptTurnDto(normalizedSpeaker, text, timestamp);
    }

    private static TranscriptTurnDto parseLineTurn(String rawLine, Instant fallbackTime) {
        String trimmed = rawLine.trim();
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx > 0 && colonIdx <= 24) {
            String candidateSpeaker = trimmed.substring(0, colonIdx).trim();
            String remainder = trimmed.substring(colonIdx + 1).trim();
            if (!candidateSpeaker.isEmpty()
                    && !remainder.isEmpty()
                    && candidateSpeaker.chars().noneMatch(Character::isWhitespace)) {
                return new TranscriptTurnDto(
                        candidateSpeaker.toUpperCase(Locale.ROOT), remainder, fallbackTime);
            }
        }
        return new TranscriptTurnDto("CALLER", trimmed, fallbackTime);
    }

    private static String firstNonBlankString(Map<?, ?> map, List<String> keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof String str && !str.isBlank()) {
                return str.trim();
            }
        }
        return null;
    }

    private static Instant extractTimestamp(Map<?, ?> map, Instant fallbackTime) {
        for (String key : List.of("timestamp", "occurredAt", "at")) {
            Object raw = map.get(key);
            if (raw instanceof Instant instant) {
                return instant;
            }
            if (raw instanceof Number number) {
                return Instant.ofEpochMilli(number.longValue());
            }
            if (raw instanceof String str && !str.isBlank()) {
                try {
                    return Instant.parse(str.trim());
                } catch (DateTimeParseException ignored) {
                    return fallbackTime;
                }
            }
        }
        return fallbackTime;
    }
}
