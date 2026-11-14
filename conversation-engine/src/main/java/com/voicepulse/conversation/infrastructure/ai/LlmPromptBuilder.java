package com.voicepulse.conversation.infrastructure.ai;

import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatFunctionDefinitionDto;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatMessageDto;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatToolDefinitionDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Builds state-aware system prompts and OpenAI/Ollama tool schemas for {@link
 * LiveLlmConversationProcessor}, enforcing SkyWay Airlines Disruption Hotline invariants (25-word
 * maximum turn limit and mandatory NATO phonetic PNR confirmation before CRM lookup).
 */
@Component
public class LlmPromptBuilder {

    private static final String BASE_SYSTEM_PROMPT =
            """
You are the SkyWay Airlines International Disruption Hotline voice triage assistant.
STRICT RULES:
1. Keep every spoken reply to 25 words or fewer. Be concise and calm.
2. Always confirm a 6-character PNR using NATO phonetic spelling before calling lookup_customer.
3. Never invent booking or flight data; use lookup_customer after caller confirmation.
4. Once passenger preferences are known, call finalize_ticket and transfer_to_human_operator.""";

    /**
     * Constructs initial chat messages for a conversational turn based on the current session
     * state.
     *
     * @param session active conversation session
     * @param utterance caller speech transcription
     * @return mutable list of initial chat messages
     */
    public List<ChatMessageDto> buildMessages(ConversationSession session, String utterance) {
        Objects.requireNonNull(session, "session cannot be null");
        Objects.requireNonNull(utterance, "utterance cannot be null");

        List<ChatMessageDto> messages = new ArrayList<>();
        messages.add(ChatMessageDto.system(BASE_SYSTEM_PROMPT + "\n" + describeState(session)));
        messages.add(ChatMessageDto.user(utterance));
        return messages;
    }

    /**
     * Returns the OpenAI/Ollama tool definitions exposed to the LLM for the current session state.
     *
     * @return list of tool definitions
     */
    public List<ChatToolDefinitionDto> buildTools() {
        ChatToolDefinitionDto lookupCustomerTool =
                ChatToolDefinitionDto.functionTool(
                        new ChatFunctionDefinitionDto(
                                "lookup_customer",
                                "Look up passenger booking and disrupted flight by confirmed"
                                        + " 6-character PNR.",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "pnr",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "6-character alphanumeric PNR")),
                                        "required",
                                        List.of("pnr"))));

        ChatToolDefinitionDto finalizeTicketTool =
                ChatToolDefinitionDto.functionTool(
                        new ChatFunctionDefinitionDto(
                                "finalize_ticket",
                                "Compile and finalize the structured airline disruption ticket.",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "rebookingPreference",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "enum",
                                                        List.of(
                                                                "NEXT_AVAILABLE",
                                                                "SAME_DAY_LATER",
                                                                "REFUND_REQUESTED")),
                                                "hotelRequired",
                                                Map.of("type", "boolean"),
                                                "specialAssistance",
                                                Map.of("type", "boolean")),
                                        "required",
                                        List.of(
                                                "rebookingPreference",
                                                "hotelRequired",
                                                "specialAssistance"))));

        ChatToolDefinitionDto transferTool =
                ChatToolDefinitionDto.functionTool(
                        new ChatFunctionDefinitionDto(
                                "transfer_to_human_operator",
                                "Transfer the active call and finalized ticket to a human"
                                        + " operator queue.",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "department",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "Optional target operator skill group")),
                                        "required",
                                        List.of())));

        return List.of(lookupCustomerTool, finalizeTicketTool, transferTool);
    }

    private static String describeState(ConversationSession session) {
        return switch (session.state()) {
            case CallSessionState.Connected c ->
                    "Current state: Connected. Greet caller and ask for their 6-character PNR.";
            case CallSessionState.Greeting g ->
                    "Current state: Greeting. Extract PNR and ask for NATO phonetic"
                            + " confirmation.";
            case CallSessionState.CollectingPassengerDetails cp ->
                    "Current state: CollectingPassengerDetails (attempt "
                            + cp.attemptCount()
                            + "). Extract 6-character PNR and confirm using NATO phonetics.";
            case CallSessionState.AwaitingBookingConfirmation awaiting ->
                    "Current state: AwaitingBookingConfirmation for PNR "
                            + awaiting.transcribedPnr()
                            + ". If caller confirms, call lookup_customer with this PNR. If caller"
                            + " denies, ask again.";
            case CallSessionState.DisruptionTriage triage ->
                    "Current state: DisruptionTriage for passenger "
                            + triage.customerName()
                            + ", PNR "
                            + triage.pnr()
                            + ", flight "
                            + triage.flightNumber()
                            + ". Capture hotel, special assistance, and rebooking preference, then"
                            + " call finalize_ticket.";
            case CallSessionState.HandoffReady handoff ->
                    "Current state: HandoffReady (ticket "
                            + handoff.ticketId()
                            + ", queue "
                            + handoff.targetSkillGroup()
                            + "). Inform caller they are being transferred.";
            case CallSessionState.Transferred tr ->
                    "Current state: Transferred to operator " + tr.operatorId() + ".";
            case CallSessionState.Terminated te ->
                    "Current state: Terminated (" + te.reason() + ").";
        };
    }
}
