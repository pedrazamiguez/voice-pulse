package com.voicepulse.conversation.infrastructure.ai;

import com.voicepulse.conversation.application.dialog.ConversationResponseGenerator;
import com.voicepulse.conversation.domain.linguistics.AcousticPnrVerifier;
import com.voicepulse.conversation.domain.linguistics.TurnBrevityValidator;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.DialogTurnResult;
import com.voicepulse.conversation.domain.ports.outbound.ConversationDialogProcessorPort;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatChoiceDto;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatCompletionResponse;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatMessageDto;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatToolCallDto;
import com.voicepulse.conversation.infrastructure.ai.dto.ChatToolDefinitionDto;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production live LLM conversational processor implementing {@link
 * ConversationDialogProcessorPort}.
 *
 * <p>Executes OpenAI/Ollama-compatible chat completion and structured tool-calling loops on Java 21
 * Virtual Threads, enforces the 25-word maximum turn ceiling via {@link TurnBrevityValidator}, and
 * falls back safely on inference errors.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "voicepulse.conversation.ai", name = "mode", havingValue = "live")
public class LiveLlmConversationProcessor implements ConversationDialogProcessorPort {

    private static final int MAX_TOOL_ROUNDS = 3;

    private final @NonNull OpenAiCompatibleChatClient chatClient;
    private final @NonNull LlmPromptBuilder promptBuilder;
    private final @NonNull LlmToolCallDispatcher toolCallDispatcher;
    private final @NonNull ConversationResponseGenerator responseGenerator;

    @Override
    public DialogTurnResult processTurn(
            ConversationSession session, String utterance, Instant timestamp) {
        Objects.requireNonNull(session, "session cannot be null");
        Objects.requireNonNull(utterance, "utterance cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        long startNanos = System.nanoTime();

        if (session.state() instanceof CallSessionState.Connected) {
            session.greet(timestamp);
            return new DialogTurnResult(
                    responseGenerator.greeting(),
                    session.state(),
                    Optional.empty(),
                    System.nanoTime() - startNanos);
        }

        if (session.state() instanceof CallSessionState.Transferred
                || session.state() instanceof CallSessionState.Terminated) {
            return new DialogTurnResult(
                    responseGenerator.transferred(),
                    session.state(),
                    session.disruptionTicket(),
                    System.nanoTime() - startNanos);
        }

        advancePreLookupStateIfNeeded(session, utterance, timestamp);

        String responseText;
        try {
            responseText = executeInferenceLoop(session, utterance, timestamp);
        } catch (RuntimeException ex) {
            log.warn(
                    "Live LLM inference failed; returning safe fallback prompt: callId={},"
                            + " state={}",
                    session.callId(),
                    session.state(),
                    ex);
            responseText = responseGenerator.fallbackError();
        }

        String enforcedResponse = enforceBrevity(responseText, session);
        Optional<AirlineDisruptionTicket> ticketOpt = session.disruptionTicket();
        long durationNanos = System.nanoTime() - startNanos;
        return new DialogTurnResult(enforcedResponse, session.state(), ticketOpt, durationNanos);
    }

    private void advancePreLookupStateIfNeeded(
            ConversationSession session, String utterance, Instant timestamp) {
        CallSessionState current = session.state();
        if (current instanceof CallSessionState.Greeting) {
            Optional<Pnr> pnrOpt = AcousticPnrVerifier.tryParse(utterance);
            session.promptPassengerDetails(1);
            pnrOpt.ifPresent(pnr -> session.awaitBookingConfirmation(pnr.value(), timestamp));
        } else if (current instanceof CallSessionState.CollectingPassengerDetails collecting) {
            Optional<Pnr> pnrOpt = AcousticPnrVerifier.tryParse(utterance);
            if (pnrOpt.isPresent()) {
                session.awaitBookingConfirmation(pnrOpt.get().value(), timestamp);
            } else {
                session.promptPassengerDetails(collecting.attemptCount() + 1);
            }
        }
    }

    private String executeInferenceLoop(
            ConversationSession session, String utterance, Instant timestamp) {
        List<ChatMessageDto> messages = promptBuilder.buildMessages(session, utterance);
        List<ChatToolDefinitionDto> tools = promptBuilder.buildTools();

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            ChatCompletionResponse completion = chatClient.complete(messages, tools);
            ChatChoiceDto choice = completion.choices().getFirst();
            ChatMessageDto assistantMessage = choice.message();
            if (assistantMessage == null) {
                throw new AiInferenceException("Live LLM returned null assistant message");
            }

            List<ChatToolCallDto> toolCalls = assistantMessage.toolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                String content = assistantMessage.content();
                return (content == null || content.isBlank())
                        ? responseGenerator.fallbackError()
                        : content.trim();
            }

            messages.add(ChatMessageDto.assistantToolCalls(assistantMessage.content(), toolCalls));
            for (ChatToolCallDto toolCall : toolCalls) {
                String toolResult = toolCallDispatcher.dispatch(session, toolCall, timestamp);
                String toolName =
                        (toolCall.function() != null && toolCall.function().name() != null)
                                ? toolCall.function().name()
                                : "unknown";
                String toolCallId = (toolCall.id() == null) ? "tool-call" : toolCall.id();
                messages.add(ChatMessageDto.toolResult(toolCallId, toolName, toolResult));
            }
        }

        log.warn(
                "Live LLM exceeded max tool rounds ({}); using fallback response: callId={}",
                MAX_TOOL_ROUNDS,
                session.callId());
        return responseGenerator.fallbackError();
    }

    private String enforceBrevity(String candidateResponse, ConversationSession session) {
        if (candidateResponse == null || candidateResponse.isBlank()) {
            return responseGenerator.fallbackError();
        }
        int wordCount = TurnBrevityValidator.countWords(candidateResponse);
        if (wordCount > TurnBrevityValidator.MAX_TURN_WORDS) {
            log.warn(
                    "Live LLM exceeded {}-word brevity ceiling (count={}); truncating response:"
                            + " callId={}",
                    TurnBrevityValidator.MAX_TURN_WORDS,
                    wordCount,
                    session.callId());
            return TurnBrevityValidator.truncateToLimit(candidateResponse);
        }
        return candidateResponse.trim();
    }
}
