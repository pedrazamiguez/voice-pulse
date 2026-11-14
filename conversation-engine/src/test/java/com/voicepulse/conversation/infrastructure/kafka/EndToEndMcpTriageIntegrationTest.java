package com.voicepulse.conversation.infrastructure.kafka;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.voicepulse.conversation.ConversationEngineApplication;
import com.voicepulse.conversation.application.dialog.ConversationResponseGenerator;
import com.voicepulse.conversation.application.registry.ConversationSessionRegistry;
import com.voicepulse.conversation.application.service.ConversationOrchestratorService;
import com.voicepulse.conversation.domain.linguistics.TurnBrevityValidator;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.ports.inbound.ProgressConversationUseCase;
import com.voicepulse.conversation.domain.ports.outbound.CallControlDispatcherPort;
import com.voicepulse.conversation.domain.ports.outbound.ConversationDialogProcessorPort;
import com.voicepulse.conversation.domain.ports.outbound.ConversationStatePublisherPort;
import com.voicepulse.conversation.domain.ports.outbound.CustomerLookupPort;
import com.voicepulse.conversation.domain.ports.outbound.DisruptionTicketPort;
import com.voicepulse.conversation.domain.ports.outbound.OperatorRoutingPort;
import com.voicepulse.conversation.domain.ports.outbound.TicketIntentPublisherPort;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.conversation.domain.vad.BargeInEvaluator;
import com.voicepulse.conversation.domain.vad.SyntheticAudioTestFactory;
import com.voicepulse.conversation.domain.vad.TrailingSilenceEvaluator;
import com.voicepulse.conversation.infrastructure.ai.LiveLlmConversationProcessor;
import com.voicepulse.conversation.infrastructure.ai.LlmPromptBuilder;
import com.voicepulse.conversation.infrastructure.ai.LlmToolCallDispatcher;
import com.voicepulse.conversation.infrastructure.ai.OpenAiCompatibleChatClient;
import com.voicepulse.conversation.infrastructure.kafka.producer.KafkaTicketIntentPublisher;
import com.voicepulse.conversation.infrastructure.mcp.McpCrmClientAdapter;
import com.voicepulse.conversation.infrastructure.mcp.McpRoutingClientAdapter;
import com.voicepulse.conversation.infrastructure.mcp.McpTicketClientAdapter;
import com.voicepulse.gateway.infrastructure.config.GatewayKafkaConfiguration;
import com.voicepulse.gateway.infrastructure.kafka.KafkaAudioFrameDispatcher;
import com.voicepulse.gateway.infrastructure.kafka.KafkaAudioFramePublisher;
import com.voicepulse.gateway.infrastructure.kafka.KafkaPublisherMetricsTracker;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.infrastructure.kafka.serializer.TicketFormFilledEventDeserializer;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import com.voicepulse.shared.model.RebookingPreference;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase 6 end-to-end integration test verifying the complete Epic 5 runtime MCP tool ecosystem and
 * Kafka ticket intent pipeline against containerized Apache Kafka KRaft and WireMock servers.
 */
@SpringBootTest(
        classes = {
            ConversationEngineApplication.class,
            GatewayKafkaConfiguration.class,
            KafkaAudioFramePublisher.class,
            KafkaAudioFrameDispatcher.class,
            KafkaPublisherMetricsTracker.class
        },
        properties = {
            "spring.main.web-application-type=none",
            "voicepulse.conversation.kafka.enabled=true",
            "voicepulse.conversation.ai.mode=mock",
            "voicepulse.gateway.audio-publisher=kafka",
            "voicepulse.gateway.kafka.topic=telephony.calls.raw",
            "voicepulse.gateway.kafka.send-timeout=5s",
            "voicepulse.gateway.kafka.buffer-memory=33554432",
            "voicepulse.gateway.kafka.max-block-timeout=5s",
            "spring.autoconfigure.exclude=",
            "voicepulse.kafka.topics.raw.name=telephony.calls.raw",
            "voicepulse.kafka.topics.raw.partitions=12",
            "voicepulse.kafka.topics.control.name=telephony.calls.control",
            "voicepulse.kafka.topics.control.partitions=12",
            "voicepulse.kafka.topics.tickets-intent.name=telephony.tickets.intent",
            "voicepulse.kafka.topics.tickets-intent.partitions=6",
            "voicepulse.kafka.topics.dlq.name=telephony.calls.dlq",
            "voicepulse.kafka.topics.dlq.partitions=3",
            "voicepulse.conversation.kafka.consumer-group=e2e-mcp-triage-group",
            "voicepulse.conversation.kafka.concurrency=3",
            "voicepulse.conversation.kafka.auto-offset-reset=earliest",
            "voicepulse.conversation.kafka.worker-pool-size=8",
            "voicepulse.conversation.mcp.connect-timeout=2s",
            "voicepulse.conversation.mcp.request-timeout=5s",
            "voicepulse.conversation.ai.timeout=5s",
            "voicepulse.conversation.ai.api-key=test-e2e-key"
        })
@Testcontainers
@DirtiesContext
@DisplayName("End-to-End MCP Tool Servers & Kafka Ticket Intent Integration Tests")
class EndToEndMcpTriageIntegrationTest {

    private static final String TICKETS_INTENT_TOPIC = "telephony.tickets.intent";
    private static final String GOLD_SKILL_GROUP = "DISRUPTION_DESK_GOLD";
    private static final String SAMPLE_TICKET_ID = "99214000-78d1-47fa-bf87-9bc40efb8911";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    private static final WireMockServer CRM_WIREMOCK =
            new WireMockServer(wireMockConfig().dynamicPort());
    private static final WireMockServer TICKET_WIREMOCK =
            new WireMockServer(wireMockConfig().dynamicPort());
    private static final WireMockServer ROUTING_WIREMOCK =
            new WireMockServer(wireMockConfig().dynamicPort());
    private static final WireMockServer LLM_WIREMOCK =
            new WireMockServer(wireMockConfig().dynamicPort());

    static {
        CRM_WIREMOCK.start();
        TICKET_WIREMOCK.start();
        ROUTING_WIREMOCK.start();
        LLM_WIREMOCK.start();
    }

    private final KafkaAudioFramePublisher audioPublisher;
    private final ProgressConversationUseCase progressConversationUseCase;
    private final ConversationSessionRegistry sessionRegistry;
    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final CustomerLookupPort customerLookupPort;
    private final DisruptionTicketPort disruptionTicketPort;
    private final OperatorRoutingPort operatorRoutingPort;
    private final TicketIntentPublisherPort ticketIntentPublisherPort;
    private final OpenAiCompatibleChatClient openAiChatClient;
    private final LlmPromptBuilder llmPromptBuilder;
    private final LlmToolCallDispatcher llmToolCallDispatcher;
    private final ConversationResponseGenerator responseGenerator;
    private final BargeInEvaluator bargeInEvaluator;
    private final CallControlDispatcherPort callControlDispatcher;
    private final ConversationStatePublisherPort statePublisher;

    private Consumer<String, TicketFormFilledEvent> ticketIntentConsumer;

    EndToEndMcpTriageIntegrationTest(
            @Autowired KafkaAudioFramePublisher audioPublisher,
            @Autowired ProgressConversationUseCase progressConversationUseCase,
            @Autowired ConversationSessionRegistry sessionRegistry,
            @Autowired KafkaListenerEndpointRegistry listenerRegistry,
            @Autowired CustomerLookupPort customerLookupPort,
            @Autowired DisruptionTicketPort disruptionTicketPort,
            @Autowired OperatorRoutingPort operatorRoutingPort,
            @Autowired TicketIntentPublisherPort ticketIntentPublisherPort,
            @Autowired OpenAiCompatibleChatClient openAiChatClient,
            @Autowired LlmPromptBuilder llmPromptBuilder,
            @Autowired LlmToolCallDispatcher llmToolCallDispatcher,
            @Autowired ConversationResponseGenerator responseGenerator,
            @Autowired BargeInEvaluator bargeInEvaluator,
            @Autowired CallControlDispatcherPort callControlDispatcher,
            @Autowired ConversationStatePublisherPort statePublisher) {
        this.audioPublisher = audioPublisher;
        this.progressConversationUseCase = progressConversationUseCase;
        this.sessionRegistry = sessionRegistry;
        this.listenerRegistry = listenerRegistry;
        this.customerLookupPort = customerLookupPort;
        this.disruptionTicketPort = disruptionTicketPort;
        this.operatorRoutingPort = operatorRoutingPort;
        this.ticketIntentPublisherPort = ticketIntentPublisherPort;
        this.openAiChatClient = openAiChatClient;
        this.llmPromptBuilder = llmPromptBuilder;
        this.llmToolCallDispatcher = llmToolCallDispatcher;
        this.responseGenerator = responseGenerator;
        this.bargeInEvaluator = bargeInEvaluator;
        this.callControlDispatcher = callControlDispatcher;
        this.statePublisher = statePublisher;
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("voicepulse.conversation.mcp.crm-base-url", CRM_WIREMOCK::baseUrl);
        registry.add("voicepulse.conversation.mcp.ticket-base-url", TICKET_WIREMOCK::baseUrl);
        registry.add("voicepulse.conversation.mcp.routing-base-url", ROUTING_WIREMOCK::baseUrl);
        registry.add(
                "voicepulse.conversation.ai.endpoint-url",
                () -> LLM_WIREMOCK.baseUrl() + "/v1/chat/completions");
    }

    @BeforeEach
    void setUp() {
        CRM_WIREMOCK.resetAll();
        TICKET_WIREMOCK.resetAll();
        ROUTING_WIREMOCK.resetAll();
        LLM_WIREMOCK.resetAll();

        assertThat(customerLookupPort).isInstanceOf(McpCrmClientAdapter.class);
        assertThat(disruptionTicketPort).isInstanceOf(McpTicketClientAdapter.class);
        assertThat(operatorRoutingPort).isInstanceOf(McpRoutingClientAdapter.class);
        assertThat(ticketIntentPublisherPort).isInstanceOf(KafkaTicketIntentPublisher.class);

        MessageListenerContainer listenerContainer =
                listenerRegistry.getListenerContainer("audioStreamListener");
        if (listenerContainer != null) {
            ContainerTestUtils.waitForAssignment(listenerContainer, 12);
        }

        ticketIntentConsumer = createTicketIntentConsumer("e2e-mcp-ticket-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        if (ticketIntentConsumer != null) {
            ticketIntentConsumer.close();
        }
    }

    @AfterAll
    static void stopWireMockServers() {
        CRM_WIREMOCK.stop();
        TICKET_WIREMOCK.stop();
        ROUTING_WIREMOCK.stop();
        LLM_WIREMOCK.stop();
    }

    @Test
    @DisplayName(
            "Scenario 1: Happy path streams PCM audio, invokes all 3 WireMock MCP servers, and"
                    + " publishes TicketFormFilledEvent")
    void happyPath_streamsAudioInvokesMcpToolServersAndPublishesTicketIntent() {
        stubCrmFoundA8F9X2();
        stubTicketFinalizeOk();
        stubRoutingTransferOk();

        CallId callId = CallId.generate();
        Instant t0 = Instant.parse("2026-11-11T09:00:00Z");
        long seq = 0L;

        // 1. Stream initial PCM audio and start greeting
        seq = streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 1);

        ConversationSession session = progressConversationUseCase.startConversation(callId, t0);
        assertThat(session.state()).isInstanceOf(CallSessionState.Greeting.class);
        assertThat(TurnBrevityValidator.isValid(session.lastBotPrompt().orElseThrow())).isTrue();
        session.completeBotTurn(t0.plusMillis(200));

        // 2. Caller states PNR -> NATO confirmation
        seq = streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 2);

        Instant t1 = t0.plusSeconds(2);
        progressConversationUseCase.progressTurn(callId, "Alpha-8-Foxtrot-9-Xray-2", t1);
        assertThat(session.state())
                .isInstanceOf(CallSessionState.AwaitingBookingConfirmation.class)
                .satisfies(
                        s ->
                                assertThat(
                                                ((CallSessionState.AwaitingBookingConfirmation) s)
                                                        .transcribedPnr())
                                        .isEqualTo("A8F9X2"));
        assertThat(session.lastBotPrompt())
                .contains("I heard Alpha-8-Foxtrot-9-Xray-2. Is that correct?");
        assertThat(TurnBrevityValidator.isValid(session.lastBotPrompt().orElseThrow())).isTrue();
        session.completeBotTurn(t1.plusMillis(200));

        // 3. Caller confirms -> mcp-crm-server lookup_customer -> DisruptionTriage
        seq = streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 3);

        Instant t2 = t1.plusSeconds(2);
        progressConversationUseCase.progressTurn(callId, "Yes", t2);
        assertThat(session.state())
                .isInstanceOf(CallSessionState.DisruptionTriage.class)
                .satisfies(
                        s -> {
                            CallSessionState.DisruptionTriage triage =
                                    (CallSessionState.DisruptionTriage) s;
                            assertThat(triage.pnr()).isEqualTo("A8F9X2");
                            assertThat(triage.customerName()).isEqualTo("Andres");
                            assertThat(triage.flightNumber()).isEqualTo("SW-104");
                        });
        assertThat(TurnBrevityValidator.isValid(session.lastBotPrompt().orElseThrow())).isTrue();
        session.completeBotTurn(t2.plusMillis(200));

        // 4. Caller requests hotel and next available flight -> finalize_ticket + routing + Kafka
        streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 4);

        Instant t3 = t2.plusSeconds(2);
        progressConversationUseCase.progressTurn(
                callId, "I need a hotel accommodation and the next available flight", t3);

        assertThat(session.state())
                .isInstanceOf(CallSessionState.HandoffReady.class)
                .satisfies(
                        s ->
                                assertThat(((CallSessionState.HandoffReady) s).targetSkillGroup())
                                        .isEqualTo(GOLD_SKILL_GROUP));
        assertThat(TurnBrevityValidator.isValid(session.lastBotPrompt().orElseThrow())).isTrue();

        AirlineDisruptionTicket ticket = session.disruptionTicket().orElseThrow();
        assertThat(ticket.pnr()).isEqualTo("A8F9X2");
        assertThat(ticket.originalFlightNumber()).isEqualTo("SW-104");
        assertThat(ticket.disruptionReason()).isEqualTo(DisruptionReason.CANCELED_WEATHER);
        assertThat(ticket.rebookingPreference()).isEqualTo(RebookingPreference.NEXT_AVAILABLE);
        assertThat(ticket.hotelRequired()).isTrue();
        assertThat(ticket.specialAssistance()).isFalse();

        // 5. Verify exact WireMock HTTP requests & Kafka TicketFormFilledEvent
        CRM_WIREMOCK.verify(
                1,
                postRequestedFor(urlEqualTo("/tools/lookup_customer"))
                        .withHeader("Content-Type", equalTo("application/json"))
                        .withRequestBody(equalToJson("{\"pnr\":\"A8F9X2\"}")));
        TICKET_WIREMOCK.verify(
                1,
                postRequestedFor(urlEqualTo("/tools/finalize_ticket"))
                        .withHeader("Content-Type", equalTo("application/json"))
                        .withRequestBody(matchingJsonPath("$.pnr", equalTo("A8F9X2")))
                        .withRequestBody(
                                matchingJsonPath("$.category", equalTo("HOTEL_ACCOMMODATION")))
                        .withRequestBody(matchingJsonPath("$.priority", equalTo("HIGH")))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.extracted_entities.hotel_required", equalTo("true")))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.extracted_entities.requires_accessibility",
                                        equalTo("false"))));
        ROUTING_WIREMOCK.verify(
                1,
                postRequestedFor(urlEqualTo("/tools/transfer_to_human_operator"))
                        .withHeader("Content-Type", equalTo("application/json"))
                        .withRequestBody(matchingJsonPath("$.call_id", equalTo(callId.toString())))
                        .withRequestBody(
                                matchingJsonPath("$.department", equalTo(GOLD_SKILL_GROUP)))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.ticket_id", equalTo(ticket.ticketId().toString()))));

        ConsumerRecord<String, TicketFormFilledEvent> ticketRecord =
                awaitTicketIntentRecord(callId);
        assertThat(ticketRecord.key()).isEqualTo(callId.toString());
        TicketFormFilledEvent event = ticketRecord.value();
        assertThat(event.callId()).isEqualTo(callId);
        assertThat(event.ticketId()).isEqualTo(ticket.ticketId());
        assertThat(event.passengerName()).isEqualTo("Andres");
        assertThat(event.pnr()).isEqualTo(new Pnr("A8F9X2"));
        assertThat(event.originalFlightNumber()).isEqualTo(new FlightNumber("SW-104"));
        assertThat(event.disruptionReason()).isEqualTo(DisruptionReason.CANCELED_WEATHER);
        assertThat(event.rebookingPreference()).isEqualTo(RebookingPreference.NEXT_AVAILABLE);
        assertThat(event.hotelRequired()).isTrue();
        assertThat(event.specialAssistance()).isFalse();
        assertThat(event.targetSkillGroup()).isEqualTo(GOLD_SKILL_GROUP);
    }

    @Test
    @DisplayName(
            "Scenario 2: Unknown PNR 404 lookup returns to CollectingPassengerDetails and recovers"
                    + " cleanly on retry")
    void unknownPnrLookup_recoversToCollectingPassengerDetailsAndCompletesTriage() {
        CRM_WIREMOCK.stubFor(
                post(urlEqualTo("/tools/lookup_customer"))
                        .withRequestBody(equalToJson("{\"pnr\":\"Z9Z9Z9\"}"))
                        .willReturn(
                                aResponse()
                                        .withStatus(404)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"error\":\"PNR not found\"}")));
        stubCrmFoundA8F9X2();
        stubTicketFinalizeOk();
        stubRoutingTransferOk();

        CallId callId = CallId.generate();
        Instant t0 = Instant.parse("2026-11-11T09:10:00Z");
        long seq = 0L;

        ConversationSession session = progressConversationUseCase.startConversation(callId, t0);
        session.completeBotTurn(t0.plusMillis(200));

        // 1. Caller states unknown PNR Z9Z9Z9 and confirms
        seq = streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 1);

        Instant t1 = t0.plusSeconds(2);
        progressConversationUseCase.progressTurn(callId, "Z9Z9Z9", t1);
        assertThat(session.state())
                .isInstanceOf(CallSessionState.AwaitingBookingConfirmation.class);
        session.completeBotTurn(t1.plusMillis(200));

        seq = streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 2);

        Instant t2 = t1.plusSeconds(2);
        progressConversationUseCase.progressTurn(callId, "Yes", t2);

        // 2. 404 transitions back to CollectingPassengerDetails with <25-word reprompt
        assertThat(session.state()).isInstanceOf(CallSessionState.CollectingPassengerDetails.class);
        String reprompt = session.lastBotPrompt().orElseThrow();
        assertThat(reprompt).contains("could not find");
        assertThat(TurnBrevityValidator.isValid(reprompt)).isTrue();
        TICKET_WIREMOCK.verify(0, postRequestedFor(urlEqualTo("/tools/finalize_ticket")));
        ROUTING_WIREMOCK.verify(
                0, postRequestedFor(urlEqualTo("/tools/transfer_to_human_operator")));
        session.completeBotTurn(t2.plusMillis(200));

        // 3. Caller retries with valid PNR A8F9X2, confirms, and completes triage
        seq = streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 3);

        Instant t3 = t2.plusSeconds(2);
        progressConversationUseCase.progressTurn(callId, "Alpha-8-Foxtrot-9-Xray-2", t3);
        assertThat(session.state())
                .isInstanceOf(CallSessionState.AwaitingBookingConfirmation.class);
        session.completeBotTurn(t3.plusMillis(200));

        seq = streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 4);

        Instant t4 = t3.plusSeconds(2);
        progressConversationUseCase.progressTurn(callId, "Yes", t4);
        assertThat(session.state()).isInstanceOf(CallSessionState.DisruptionTriage.class);
        session.completeBotTurn(t4.plusMillis(200));

        streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 5);

        Instant t5 = t4.plusSeconds(2);
        progressConversationUseCase.progressTurn(
                callId, "I need a hotel and the next available flight", t5);

        assertThat(session.state()).isInstanceOf(CallSessionState.HandoffReady.class);
        CRM_WIREMOCK.verify(2, postRequestedFor(urlEqualTo("/tools/lookup_customer")));
        TICKET_WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/tools/finalize_ticket")));
        ROUTING_WIREMOCK.verify(
                1, postRequestedFor(urlEqualTo("/tools/transfer_to_human_operator")));

        ConsumerRecord<String, TicketFormFilledEvent> ticketRecord =
                awaitTicketIntentRecord(callId);
        assertThat(ticketRecord.key()).isEqualTo(callId.toString());
        assertThat(ticketRecord.value().pnr()).isEqualTo(new Pnr("A8F9X2"));
        assertThat(ticketRecord.value().targetSkillGroup()).isEqualTo(GOLD_SKILL_GROUP);
    }

    @Test
    @DisplayName(
            "Scenario 3: Live LLM mode executes WireMock tool calls, enforces 25-word brevity, and"
                    + " completes MCP + Kafka pipeline")
    void liveLlmMode_executesToolCallsEnforcesBrevityAndPublishesTicketIntent() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.main.web-application-type=none",
                        "spring.autoconfigure.exclude="
                            + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                        "voicepulse.conversation.kafka.enabled=false",
                        "voicepulse.conversation.ai.mode=live",
                        "voicepulse.conversation.ai.endpoint-url="
                                + LLM_WIREMOCK.baseUrl()
                                + "/v1/chat/completions")
                .withUserConfiguration(ConversationEngineApplication.class)
                .run(
                        context -> {
                            ConversationDialogProcessorPort port =
                                    context.getBean(ConversationDialogProcessorPort.class);
                            assertThat(port).isInstanceOf(LiveLlmConversationProcessor.class);
                        });

        stubCrmFoundA8F9X2();
        stubTicketFinalizeOk();
        stubRoutingTransferOk();
        stubLiveLlmToolCallingScenario();

        LiveLlmConversationProcessor liveProcessor =
                new LiveLlmConversationProcessor(
                        openAiChatClient,
                        llmPromptBuilder,
                        llmToolCallDispatcher,
                        responseGenerator);
        ConversationOrchestratorService liveOrchestrator =
                new ConversationOrchestratorService(
                        sessionRegistry,
                        liveProcessor,
                        bargeInEvaluator,
                        callControlDispatcher,
                        statePublisher);

        CallId callId = CallId.generate();
        Instant t0 = Instant.parse("2026-11-11T09:20:00Z");
        long seq = 0L;

        ConversationSession session = liveOrchestrator.startConversation(callId, t0);
        assertThat(session.state()).isInstanceOf(CallSessionState.Greeting.class);
        session.completeBotTurn(t0.plusMillis(200));

        // Turn 1: PNR statement -> NATO confirmation via LLM WireMock
        seq = streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 1);

        Instant t1 = t0.plusSeconds(2);
        liveOrchestrator.progressTurn(callId, "Alpha-8-Foxtrot-9-Xray-2", t1);
        assertThat(session.state())
                .isInstanceOf(CallSessionState.AwaitingBookingConfirmation.class);
        assertThat(session.lastBotPrompt())
                .contains("I heard Alpha-8-Foxtrot-9-Xray-2. Is that correct?");
        session.completeBotTurn(t1.plusMillis(200));

        // Turn 2: Confirmation -> lookup_customer tool call + >25-word response truncation check
        seq = streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 2);

        Instant t2 = t1.plusSeconds(2);
        liveOrchestrator.progressTurn(callId, "Yes, that is correct.", t2);
        assertThat(session.state()).isInstanceOf(CallSessionState.DisruptionTriage.class);
        String truncatedPrompt = session.lastBotPrompt().orElseThrow();
        assertThat(TurnBrevityValidator.countWords(truncatedPrompt))
                .isEqualTo(TurnBrevityValidator.MAX_TURN_WORDS);
        assertThat(TurnBrevityValidator.isValid(truncatedPrompt)).isTrue();
        session.completeBotTurn(t2.plusMillis(200));

        // Turn 3: Preferences -> finalize_ticket + transfer_to_human_operator tool calls -> Kafka
        streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 3);

        Instant t3 = t2.plusSeconds(2);
        liveOrchestrator.progressTurn(
                callId, "I need a hotel room and the next available flight.", t3);
        assertThat(session.state())
                .isInstanceOf(CallSessionState.HandoffReady.class)
                .satisfies(
                        s ->
                                assertThat(((CallSessionState.HandoffReady) s).targetSkillGroup())
                                        .isEqualTo(GOLD_SKILL_GROUP));
        assertThat(TurnBrevityValidator.isValid(session.lastBotPrompt().orElseThrow())).isTrue();

        LLM_WIREMOCK.verify(5, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        CRM_WIREMOCK.verify(
                1,
                postRequestedFor(urlEqualTo("/tools/lookup_customer"))
                        .withRequestBody(equalToJson("{\"pnr\":\"A8F9X2\"}")));
        TICKET_WIREMOCK.verify(1, postRequestedFor(urlEqualTo("/tools/finalize_ticket")));
        ROUTING_WIREMOCK.verify(
                1, postRequestedFor(urlEqualTo("/tools/transfer_to_human_operator")));

        ConsumerRecord<String, TicketFormFilledEvent> ticketRecord =
                awaitTicketIntentRecord(callId);
        assertThat(ticketRecord.key()).isEqualTo(callId.toString());
        assertThat(ticketRecord.value().pnr()).isEqualTo(new Pnr("A8F9X2"));
        assertThat(ticketRecord.value().hotelRequired()).isTrue();
        assertThat(ticketRecord.value().targetSkillGroup()).isEqualTo(GOLD_SKILL_GROUP);
    }

    private void stubCrmFoundA8F9X2() {
        CRM_WIREMOCK.stubFor(
                post(urlEqualTo("/tools/lookup_customer"))
                        .withRequestBody(equalToJson("{\"pnr\":\"A8F9X2\"}"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "pnr": "A8F9X2",
                                                  "customer_name": "Andres",
                                                  "frequent_flyer_tier": "Gold",
                                                  "active_itinerary": {
                                                    "flight_number": "SW-104",
                                                    "origin": "CTU",
                                                    "destination": "MAD",
                                                    "status": "CANCELED_WEATHER",
                                                    "original_departure": "2026-11-01T01:45:00Z"
                                                  }
                                                }
                                                """)));
    }

    private void stubTicketFinalizeOk() {
        TICKET_WIREMOCK.stubFor(
                post(urlEqualTo("/tools/finalize_ticket"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "ticket_id": "%s",
                                                  "pnr": "A8F9X2",
                                                  "category": "HOTEL_ACCOMMODATION",
                                                  "priority": "HIGH",
                                                  "status": "FINALIZED",
                                                  "submitted_at": "2026-11-11T09:05:00Z"
                                                }
                                                """
                                                        .formatted(SAMPLE_TICKET_ID))));
    }

    private void stubRoutingTransferOk() {
        ROUTING_WIREMOCK.stubFor(
                post(urlEqualTo("/tools/transfer_to_human_operator"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "status": "QUEUED",
                                                  "estimated_wait_seconds": 45,
                                                  "queue_position": 2
                                                }
                                                """)));
    }

    private void stubLiveLlmToolCallingScenario() {
        LLM_WIREMOCK.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .inScenario("live-llm-e2e")
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
      "content": "I heard Alpha-8-Foxtrot-9-Xray-2. Is that correct?"
    },
    "finish_reason": "stop"
  }]
}
"""))
                        .willSetStateTo("awaiting-confirm"));

        LLM_WIREMOCK.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .inScenario("live-llm-e2e")
                        .whenScenarioStateIs("awaiting-confirm")
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

        String verboseFollowUp =
                "Flight SW-104 from Chengdu to Madrid was cancelled due to severe weather today"
                        + " and we are very sorry for the inconvenience caused to your travel"
                        + " plans would you like a hotel or rebooking";
        LLM_WIREMOCK.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .inScenario("live-llm-e2e")
                        .whenScenarioStateIs("after-lookup")
                        .withRequestBody(containing("call_lookup_1"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                  "id": "chatcmpl-3",
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
                                                        .formatted(verboseFollowUp)))
                        .willSetStateTo("awaiting-triage"));

        LLM_WIREMOCK.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .inScenario("live-llm-e2e")
                        .whenScenarioStateIs("awaiting-triage")
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
{
  "id": "chatcmpl-4",
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

        LLM_WIREMOCK.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .inScenario("live-llm-e2e")
                        .whenScenarioStateIs("after-finalize")
                        .withRequestBody(containing("call_finalize_1"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
{
  "id": "chatcmpl-5",
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
    }

    private long streamSpeechAndTrailingSilence(
            CallId callId, long startSeq, int speechFrames, int silenceFrames) {
        byte[] speechPayload = SyntheticAudioTestFactory.generateSpeechBurstFrame((short) 9000);
        byte[] silencePayload = SyntheticAudioTestFactory.generateSilenceFrame();
        long seq = startSeq;

        for (int i = 0; i < speechFrames; i++) {
            audioPublisher.publishAudioFrame(callId, seq++, speechPayload);
        }
        for (int i = 0; i < silenceFrames; i++) {
            audioPublisher.publishAudioFrame(callId, seq++, silencePayload);
        }
        return seq;
    }

    private void awaitTurnBoundaryCount(CallId callId, int expectedTurnBoundaries) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            TrailingSilenceEvaluator evaluator =
                                    sessionRegistry.getOrCreateTrailingSilenceEvaluator(callId);
                            assertThat(evaluator.completedTurnCount())
                                    .isEqualTo(expectedTurnBoundaries);
                        });
    }

    private ConsumerRecord<String, TicketFormFilledEvent> awaitTicketIntentRecord(CallId callId) {
        List<ConsumerRecord<String, TicketFormFilledEvent>> matched = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            ConsumerRecords<String, TicketFormFilledEvent> records =
                                    ticketIntentConsumer.poll(Duration.ofMillis(200));
                            for (ConsumerRecord<String, TicketFormFilledEvent> record : records) {
                                if (callId.toString().equals(record.key())) {
                                    matched.add(record);
                                }
                            }
                            assertThat(matched).isNotEmpty();
                        });
        return matched.getFirst();
    }

    private Consumer<String, TicketFormFilledEvent> createTicketIntentConsumer(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                TicketFormFilledEventDeserializer.class);

        DefaultKafkaConsumerFactory<String, TicketFormFilledEvent> factory =
                new DefaultKafkaConsumerFactory<>(props);
        Consumer<String, TicketFormFilledEvent> consumer = factory.createConsumer();
        consumer.subscribe(Collections.singletonList(TICKETS_INTENT_TOPIC));
        return consumer;
    }
}
