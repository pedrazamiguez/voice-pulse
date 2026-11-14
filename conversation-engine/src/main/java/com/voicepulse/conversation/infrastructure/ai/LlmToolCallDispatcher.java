package com.voicepulse.conversation.infrastructure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.conversation.application.dialog.DisruptionTriageCoordinator;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatToolCallDto;
import com.voicepulse.conversation.infrastructure.ai.dto.FinalizeTicketToolArgs;
import com.voicepulse.conversation.infrastructure.ai.dto.LookupCustomerToolArgs;
import com.voicepulse.conversation.infrastructure.ai.dto.TransferOperatorToolArgs;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Dispatches structured LLM tool calls ({@code lookup_customer}, {@code finalize_ticket}, {@code
 * transfer_to_human_operator}) through {@link DisruptionTriageCoordinator} and serializes tool
 * responses back into the chat completion loop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmToolCallDispatcher {

    private final @NonNull DisruptionTriageCoordinator triageCoordinator;
    private final @NonNull ObjectMapper objectMapper;

    /**
     * Executes a single LLM tool call against the domain session and outbound MCP ports.
     *
     * @param session active conversation session
     * @param toolCall tool invocation requested by the LLM
     * @param timestamp turn timestamp
     * @return JSON-encoded tool execution output for the follow-up LLM turn
     */
    public String dispatch(
            ConversationSession session, ChatToolCallDto toolCall, Instant timestamp) {
        Objects.requireNonNull(session, "session cannot be null");
        Objects.requireNonNull(toolCall, "toolCall cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        if (toolCall.function() == null || toolCall.function().name() == null) {
            throw new AiInferenceException("Malformed LLM tool call missing function name");
        }

        String toolName = toolCall.function().name();
        String rawArgs =
                (toolCall.function().arguments() == null
                                || toolCall.function().arguments().isBlank())
                        ? "{}"
                        : toolCall.function().arguments();

        return switch (toolName) {
            case "lookup_customer" -> handleLookupCustomer(session, rawArgs, timestamp);
            case "finalize_ticket" -> handleFinalizeTicket(session, rawArgs, timestamp);
            case "transfer_to_human_operator" ->
                    handleTransferOperator(session, rawArgs, timestamp);
            default -> serializeResult(Map.of("error", "Unknown tool: " + toolName));
        };
    }

    private String handleLookupCustomer(
            ConversationSession session, String rawArgs, Instant timestamp) {
        LookupCustomerToolArgs args = parseArgs(rawArgs, LookupCustomerToolArgs.class);
        if (args.pnr() == null || args.pnr().isBlank()) {
            return serializeResult(Map.of("found", false, "error", "PNR is required"));
        }

        if (!(session.state() instanceof CallSessionState.AwaitingBookingConfirmation)) {
            return serializeResult(
                    Map.of(
                            "found",
                            false,
                            "error",
                            "PNR must be acoustically confirmed before lookup_customer"));
        }

        Pnr pnr = new Pnr(args.pnr());
        Optional<CustomerProfile> profileOpt =
                triageCoordinator.lookupAndConfirmBooking(session, pnr, timestamp);
        if (profileOpt.isEmpty()) {
            return serializeResult(Map.of("found", false, "pnr", pnr.value()));
        }

        CustomerProfile profile = profileOpt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("pnr", profile.pnr().value());
        result.put("customerName", profile.customerName());
        result.put("frequentFlyerTier", profile.frequentFlyerTier().name());
        result.put("flightNumber", profile.activeItinerary().flightNumber().value());
        result.put("disruptionReason", profile.activeItinerary().disruptionReason().name());
        return serializeResult(result);
    }

    private String handleFinalizeTicket(
            ConversationSession session, String rawArgs, Instant timestamp) {
        FinalizeTicketToolArgs args = parseArgs(rawArgs, FinalizeTicketToolArgs.class);
        RebookingPreference preference = parsePreference(args.rebookingPreference());

        AirlineDisruptionTicket ticket =
                triageCoordinator.finalizeAndRouteTriage(
                        session,
                        preference,
                        args.hotelRequired(),
                        args.specialAssistance(),
                        timestamp);

        String skillGroup =
                (session.state() instanceof CallSessionState.HandoffReady handoff)
                        ? handoff.targetSkillGroup()
                        : "";

        return serializeResult(
                Map.of(
                        "status",
                        "FINALIZED",
                        "ticketId",
                        ticket.ticketId().toString(),
                        "targetSkillGroup",
                        skillGroup));
    }

    private String handleTransferOperator(
            ConversationSession session, String rawArgs, Instant timestamp) {
        TransferOperatorToolArgs args = parseArgs(rawArgs, TransferOperatorToolArgs.class);

        if (session.state() instanceof CallSessionState.HandoffReady handoff) {
            return serializeResult(
                    Map.of(
                            "status",
                            "QUEUED",
                            "ticketId",
                            handoff.ticketId(),
                            "department",
                            handoff.targetSkillGroup()));
        }

        AirlineDisruptionTicket ticket =
                triageCoordinator.finalizeAndRouteTriage(
                        session,
                        RebookingPreference.NEXT_AVAILABLE,
                        false,
                        false,
                        args.department(),
                        timestamp);

        String skillGroup =
                (session.state() instanceof CallSessionState.HandoffReady handoff)
                        ? handoff.targetSkillGroup()
                        : "";

        return serializeResult(
                Map.of(
                        "status",
                        "QUEUED",
                        "ticketId",
                        ticket.ticketId().toString(),
                        "department",
                        skillGroup));
    }

    private <T> T parseArgs(String rawArgs, Class<T> targetType) {
        try {
            return objectMapper.readValue(rawArgs, targetType);
        } catch (JsonProcessingException ex) {
            throw new AiInferenceException(
                    "Failed to parse LLM tool arguments for " + targetType.getSimpleName(), ex);
        }
    }

    private String serializeResult(Map<String, Object> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new AiInferenceException("Failed to serialize LLM tool result", ex);
        }
    }

    private static RebookingPreference parsePreference(String rawPreference) {
        if (rawPreference == null || rawPreference.isBlank()) {
            return RebookingPreference.NEXT_AVAILABLE;
        }
        String normalized = rawPreference.trim().toUpperCase(Locale.ROOT);
        for (RebookingPreference value : RebookingPreference.values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        return RebookingPreference.NEXT_AVAILABLE;
    }
}
