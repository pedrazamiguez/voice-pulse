package com.voicepulse.conversation.infrastructure.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.voicepulse.conversation.application.dialog.ConversationResponseGenerator;
import com.voicepulse.conversation.application.dialog.DisruptionTriageCoordinator;
import com.voicepulse.conversation.domain.linguistics.TurnBrevityValidator;
import com.voicepulse.conversation.domain.model.ActiveItinerary;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.conversation.domain.model.DialogTurnResult;
import com.voicepulse.conversation.domain.model.FrequentFlyerTier;
import com.voicepulse.conversation.domain.model.OperatorHandoffReceipt;
import com.voicepulse.conversation.domain.model.TicketSubmissionReceipt;
import com.voicepulse.conversation.domain.ports.outbound.CustomerLookupPort;
import com.voicepulse.conversation.domain.ports.outbound.DisruptionTicketPort;
import com.voicepulse.conversation.domain.ports.outbound.OperatorRoutingPort;
import com.voicepulse.conversation.domain.ports.outbound.TicketIntentPublisherPort;
import com.voicepulse.conversation.domain.service.SkillGroupRoutingPolicy;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.conversation.infrastructure.ai.config.AiInferenceProperties;
import com.voicepulse.conversation.infrastructure.ai.config.AiProcessingMode;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.instancio.Instancio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("LiveLlmConversationProcessor WireMock Integration Tests")
class LiveLlmConversationProcessorWireMockTest {

    @Mock private CustomerLookupPort customerLookupPort;
    @Mock private DisruptionTicketPort disruptionTicketPort;
    @Mock private OperatorRoutingPort operatorRoutingPort;
    @Mock private TicketIntentPublisherPort ticketIntentPublisherPort;

    @Captor private ArgumentCaptor<TicketFormFilledEvent> eventCaptor;

    private WireMockServer wireMockServer;
    private ExecutorService virtualThreadExecutor;
    private ObjectMapper objectMapper;
    private DisruptionTriageCoordinator triageCoordinator;
    private LiveLlmConversationProcessor processor;
    private CallId callId;
    private Instant now;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        objectMapper = KafkaJsonCodecProvider.objectMapper();
        triageCoordinator =
                new DisruptionTriageCoordinator(
                        customerLookupPort,
                        disruptionTicketPort,
                        operatorRoutingPort,
                        ticketIntentPublisherPort,
                        new SkillGroupRoutingPolicy());
        processor = createProcessor(virtualThreadExecutor, "test-api-key", Duration.ofMillis(600));

        callId = CallId.generate();
        now = Instant.now();

        CustomerProfile profile =
                Instancio.of(CustomerProfile.class)
                        .set(field(CustomerProfile::pnr), new Pnr("A8F9X2"))
                        .set(field(CustomerProfile::customerName), "Andres")
                        .set(field(CustomerProfile::frequentFlyerTier), FrequentFlyerTier.GOLD)
                        .set(
                                field(CustomerProfile::activeItinerary),
                                Instancio.of(ActiveItinerary.class)
                                        .set(
                                                field(ActiveItinerary::flightNumber),
                                                new FlightNumber("SW-104"))
                                        .set(
                                                field(ActiveItinerary::disruptionReason),
                                                DisruptionReason.CANCELED_WEATHER)
                                        .create())
                        .create();
        lenient()
                .when(customerLookupPort.lookupCustomerByPnr(new Pnr("A8F9X2")))
                .thenReturn(Optional.of(profile));

        TicketSubmissionReceipt receipt =
                Instancio.of(TicketSubmissionReceipt.class)
                        .set(field(TicketSubmissionReceipt::pnr), new Pnr("A8F9X2"))
                        .create();
        lenient()
                .when(disruptionTicketPort.finalizeTicket(any(AirlineDisruptionTicket.class)))
                .thenReturn(receipt);

        OperatorHandoffReceipt handoff =
                Instancio.of(OperatorHandoffReceipt.class)
                        .set(field(OperatorHandoffReceipt::callId), callId)
                        .set(field(OperatorHandoffReceipt::department), "DISRUPTION_DESK_GOLD")
                        .create();
        lenient()
                .when(
                        operatorRoutingPort.transferToHumanOperator(
                                eq(callId), any(String.class), any(UUID.class)))
                .thenReturn(handoff);
    }

    @AfterEach
    void tearDown() {
        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.close();
        }
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName(
            "Executes lookup_customer tool call on Virtual Thread and transitions to"
                    + " DisruptionTriage")
    void processTurn_lookupCustomerFound_executesOnVirtualThreadAndTransitions() {
        AtomicBoolean ranOnVirtualThread = new AtomicBoolean(false);
        try (ExecutorService trackingExecutor =
                Executors.newThreadPerTaskExecutor(
                        runnable ->
                                Thread.ofVirtual()
                                        .unstarted(
                                                () -> {
                                                    ranOnVirtualThread.set(
                                                            Thread.currentThread().isVirtual());
                                                    runnable.run();
                                                }))) {
            LiveLlmConversationProcessor trackedProcessor =
                    createProcessor(trackingExecutor, "secret-token", Duration.ofSeconds(2));

            wireMockServer.stubFor(
                    post(urlEqualTo("/v1/chat/completions"))
                            .inScenario("lookup-found")
                            .whenScenarioStateIs(STARTED)
                            .withHeader("Authorization", equalTo("Bearer secret-token"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
{
  "id": "chatcmpl-1",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_lookup_1",
        "type": "function",
        "function": {
          "name": "lookup_customer",
          "arguments": "{\\"pnr\\":\\"A8F9X2\\"}"
        }
      }]
    },
    "finish_reason": "tool_calls"
  }]
}
"""))
                            .willSetStateTo("after-lookup"));

            wireMockServer.stubFor(
                    post(urlEqualTo("/v1/chat/completions"))
                            .inScenario("lookup-found")
                            .whenScenarioStateIs("after-lookup")
                            .withRequestBody(containing("call_lookup_1"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
{
  "id": "chatcmpl-2",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": "Flight SW-104 was cancelled due to weather. Would you like rebooking or a hotel?"
    },
    "finish_reason": "stop"
  }]
}
""")));

            ConversationSession session = ConversationSession.start(callId, now);
            session.greet(now);
            session.promptPassengerDetails(1);
            session.awaitBookingConfirmation("A8F9X2", now);

            DialogTurnResult result = trackedProcessor.processTurn(session, "Yes, correct.", now);

            assertThat(ranOnVirtualThread.get()).isTrue();
            assertThat(result.targetState()).isInstanceOf(CallSessionState.DisruptionTriage.class);
            assertThat(result.responseText()).contains("SW-104");
            assertThat(TurnBrevityValidator.isValid(result.responseText())).isTrue();
            verify(customerLookupPort).lookupCustomerByPnr(new Pnr("A8F9X2"));
            wireMockServer.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        }
    }

    @Test
    @DisplayName("Transitions back to CollectingPassengerDetails when lookup_customer finds no PNR")
    void processTurn_lookupCustomerNotFound_revertsToCollectingPassengerDetails() {
        when(customerLookupPort.lookupCustomerByPnr(new Pnr("Z9Z9Z9")))
                .thenReturn(Optional.empty());

        wireMockServer.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .inScenario("lookup-missing")
                        .whenScenarioStateIs(STARTED)
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "id": "chatcmpl-1",
                                                  "choices": [{
                                                    "index": 0,
                                                    "message": {
                                                      "role": "assistant",
                                                      "content": null,
                                                      "tool_calls": [{
                                                        "id": "call_lookup_missing",
                                                        "type": "function",
                                                        "function": {
                                                          "name": "lookup_customer",
                                                          "arguments": "{\\"pnr\\":\\"Z9Z9Z9\\"}"
                                                        }
                                                      }]
                                                    },
                                                    "finish_reason": "tool_calls"
                                                  }]
                                                }
                                                """))
                        .willSetStateTo("after-missing"));

        wireMockServer.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .inScenario("lookup-missing")
                        .whenScenarioStateIs("after-missing")
                        .withRequestBody(containing("\"found\":false"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
{
  "id": "chatcmpl-2",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": "I could not find that PNR. Please state your six-character code again."
    },
    "finish_reason": "stop"
  }]
}
""")));

        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);
        session.promptPassengerDetails(1);
        session.awaitBookingConfirmation("Z9Z9Z9", now);

        DialogTurnResult result = processor.processTurn(session, "Yes.", now);

        assertThat(result.targetState())
                .isInstanceOf(CallSessionState.CollectingPassengerDetails.class);
        assertThat(TurnBrevityValidator.isValid(result.responseText())).isTrue();
        verify(disruptionTicketPort, never()).finalizeTicket(any());
    }

    @Test
    @DisplayName(
            "Executes finalize_ticket and transfer_to_human_operator tool calls and publishes"
                    + " TicketFormFilledEvent")
    void processTurn_finalizeAndTransferTools_invokesMcpAndKafka() {
        wireMockServer.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .inScenario("triage-complete")
                        .whenScenarioStateIs(STARTED)
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
{
  "id": "chatcmpl-1",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        {
          "id": "call_finalize_1",
          "type": "function",
          "function": {
            "name": "finalize_ticket",
            "arguments": "{\\"rebookingPreference\\":\\"NEXT_AVAILABLE\\",\\"hotelRequired\\":true,\\"specialAssistance\\":false}"
          }
        },
        {
          "id": "call_transfer_1",
          "type": "function",
          "function": {
            "name": "transfer_to_human_operator",
            "arguments": "{}"
          }
        }
      ]
    },
    "finish_reason": "tool_calls"
  }]
}
"""))
                        .willSetStateTo("after-finalize"));

        wireMockServer.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .inScenario("triage-complete")
                        .whenScenarioStateIs("after-finalize")
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
{
  "id": "chatcmpl-2",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": "Your hotel request is ready. Transferring you to our priority team now."
    },
    "finish_reason": "stop"
  }]
}
""")));

        ConversationSession session = createSessionInDisruptionTriage();

        DialogTurnResult result =
                processor.processTurn(session, "I need a hotel and the next flight.", now);

        assertThat(result.targetState()).isInstanceOf(CallSessionState.HandoffReady.class);
        assertThat(result.disruptionTicket()).isPresent();
        AirlineDisruptionTicket ticket = result.disruptionTicket().orElseThrow();
        assertThat(ticket.hotelRequired()).isTrue();
        assertThat(ticket.rebookingPreference()).isEqualTo(RebookingPreference.NEXT_AVAILABLE);
        assertThat(TurnBrevityValidator.isValid(result.responseText())).isTrue();

        verify(disruptionTicketPort).finalizeTicket(ticket);
        verify(operatorRoutingPort)
                .transferToHumanOperator(callId, "DISRUPTION_DESK_GOLD", ticket.ticketId());
        verify(ticketIntentPublisherPort).publishTicketFilled(eventCaptor.capture());
        assertThat(eventCaptor.getValue().targetSkillGroup()).isEqualTo("DISRUPTION_DESK_GOLD");
    }

    @Test
    @DisplayName("Truncates LLM response exceeding 25-word brevity ceiling")
    void processTurn_verboseLlmResponse_enforces25WordBrevityLimit() {
        String verboseText =
                "one two three four five six seven eight nine ten eleven twelve thirteen fourteen"
                    + " fifteen sixteen seventeen eighteen nineteen twenty twenty-one twenty-two"
                    + " twenty-three twenty-four twenty-five twenty-six twenty-seven twenty-eight";

        wireMockServer.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "id": "chatcmpl-verbose",
                                                  "choices": [{
                                                    "index": 0,
                                                    "message": {
                                                      "role": "assistant",
                                                      "content": "%s"
                                                    },
                                                    "finish_reason": "stop"
                                                  }]
                                                }
                                                """
                                                        .formatted(verboseText))));

        ConversationSession session = createSessionInDisruptionTriage();

        DialogTurnResult result = processor.processTurn(session, "What happened?", now);

        assertThat(TurnBrevityValidator.countWords(result.responseText()))
                .isEqualTo(TurnBrevityValidator.MAX_TURN_WORDS);
        assertThat(TurnBrevityValidator.isValid(result.responseText())).isTrue();
        assertThat(result.responseText()).endsWith("twenty-five");
    }

    @Test
    @DisplayName("Falls back safely when LLM endpoint returns 500 or times out")
    void processTurn_endpointErrorsAndTimeouts_returnsSafeFallbackPrompt() {
        wireMockServer.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"boom\"}")));

        ConversationSession session = createSessionInDisruptionTriage();
        DialogTurnResult errorResult = processor.processTurn(session, "Hello?", now);

        assertThat(errorResult.responseText()).contains("Connecting you to an agent now");
        assertThat(TurnBrevityValidator.isValid(errorResult.responseText())).isTrue();

        wireMockServer.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withFixedDelay(1200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{}")));

        DialogTurnResult timeoutResult = processor.processTurn(session, "Hello?", now);
        assertThat(timeoutResult.responseText()).contains("Connecting you to an agent now");
        assertThat(TurnBrevityValidator.isValid(timeoutResult.responseText())).isTrue();
    }

    private LiveLlmConversationProcessor createProcessor(
            ExecutorService executor, String apiKey, Duration timeout) {
        AiInferenceProperties properties =
                new AiInferenceProperties(
                        AiProcessingMode.LIVE,
                        wireMockServer.baseUrl() + "/v1/chat/completions",
                        "llama3.1",
                        timeout,
                        apiKey);
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(timeout).executor(executor).build();
        OpenAiCompatibleChatClient chatClient =
                new OpenAiCompatibleChatClient(httpClient, executor, objectMapper, properties);
        return new LiveLlmConversationProcessor(
                chatClient,
                new LlmPromptBuilder(),
                new LlmToolCallDispatcher(triageCoordinator, objectMapper),
                new ConversationResponseGenerator());
    }

    private ConversationSession createSessionInDisruptionTriage() {
        ConversationSession session = ConversationSession.start(callId, now);
        session.greet(now);
        session.promptPassengerDetails(1);
        session.awaitBookingConfirmation("A8F9X2", now);

        CustomerProfile profile =
                Instancio.of(CustomerProfile.class)
                        .set(field(CustomerProfile::pnr), new Pnr("A8F9X2"))
                        .set(field(CustomerProfile::customerName), "Andres")
                        .set(field(CustomerProfile::frequentFlyerTier), FrequentFlyerTier.GOLD)
                        .set(
                                field(CustomerProfile::activeItinerary),
                                Instancio.of(ActiveItinerary.class)
                                        .set(
                                                field(ActiveItinerary::flightNumber),
                                                new FlightNumber("SW-104"))
                                        .set(
                                                field(ActiveItinerary::disruptionReason),
                                                DisruptionReason.CANCELED_WEATHER)
                                        .create())
                        .create();
        session.confirmBooking(profile, now);
        return session;
    }
}
