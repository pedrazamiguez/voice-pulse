package com.voicepulse.conversation.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voicepulse.conversation.ConversationEngineApplication;
import com.voicepulse.conversation.application.registry.ConversationSessionRegistry;
import com.voicepulse.conversation.domain.event.CallStateTransitioned;
import com.voicepulse.conversation.domain.event.TurnCompleted;
import com.voicepulse.conversation.domain.linguistics.TurnBrevityValidator;
import com.voicepulse.conversation.domain.model.ActiveItinerary;
import com.voicepulse.conversation.domain.model.AirlineDisruptionTicket;
import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.conversation.domain.model.FrequentFlyerTier;
import com.voicepulse.conversation.domain.model.OperatorHandoffReceipt;
import com.voicepulse.conversation.domain.model.TicketSubmissionReceipt;
import com.voicepulse.conversation.domain.ports.inbound.ProcessAudioChunkUseCase;
import com.voicepulse.conversation.domain.ports.inbound.ProgressConversationUseCase;
import com.voicepulse.conversation.domain.ports.outbound.CallControlDispatcherPort;
import com.voicepulse.conversation.domain.ports.outbound.ConversationStatePublisherPort;
import com.voicepulse.conversation.domain.ports.outbound.CustomerLookupPort;
import com.voicepulse.conversation.domain.ports.outbound.DisruptionTicketPort;
import com.voicepulse.conversation.domain.ports.outbound.OperatorRoutingPort;
import com.voicepulse.conversation.domain.statemachine.CallSessionState;
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.conversation.domain.vad.SyntheticAudioTestFactory;
import com.voicepulse.conversation.domain.vad.TrailingSilenceEvaluator;
import com.voicepulse.conversation.domain.vad.TurnPhase;
import com.voicepulse.conversation.infrastructure.kafka.producer.KafkaCallControlDispatcher;
import com.voicepulse.conversation.infrastructure.kafka.sequence.MonotonicSequenceVerifier;
import com.voicepulse.conversation.infrastructure.kafka.sequence.SequenceVerificationMetrics;
import com.voicepulse.gateway.infrastructure.config.GatewayKafkaConfiguration;
import com.voicepulse.gateway.infrastructure.kafka.KafkaAudioFrameDispatcher;
import com.voicepulse.gateway.infrastructure.kafka.KafkaAudioFramePublisher;
import com.voicepulse.gateway.infrastructure.kafka.KafkaPublisherMetricsTracker;
import com.voicepulse.shared.event.AudioChunkEnvelope;
import com.voicepulse.shared.event.BargeInInterrupt;
import com.voicepulse.shared.event.CallControlEnvelope;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.infrastructure.kafka.serializer.AudioChunkEnvelopeDeserializer;
import com.voicepulse.shared.infrastructure.kafka.serializer.CallControlEnvelopeDeserializer;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.instancio.Instancio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end multi-module integration test verifying the complete SkyWay Airlines conversational
 * pipeline over Apache Kafka KRaft (Testcontainers).
 *
 * <p>Validates 20ms Linear PCM audio streaming on {@code telephony.calls.raw}, VAD 500ms trailing
 * silence turn boundaries, NATO phonetic PNR verification, 25-word prompt brevity, mid-stream
 * caller barge-in interruption on {@code telephony.calls.control}, {@link AirlineDisruptionTicket}
 * compilation, and {@link TicketFormFilledEvent} publication on {@code telephony.tickets.intent}.
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
            "voicepulse.conversation.kafka.consumer-group=e2e-conversation-dialog-group",
            "voicepulse.conversation.kafka.concurrency=3",
            "voicepulse.conversation.kafka.auto-offset-reset=earliest",
            "voicepulse.conversation.kafka.worker-pool-size=8"
        })
@Testcontainers
@DirtiesContext
@DisplayName("End-to-End Conversational Dialog & Barge-In Testcontainers Integration Tests")
class EndToEndConversationDialogIntegrationTest {

    private static final String RAW_TOPIC = "telephony.calls.raw";
    private static final String CONTROL_TOPIC = "telephony.calls.control";
    private static final String TICKETS_INTENT_TOPIC = "telephony.tickets.intent";
    private static final long MAX_TURN_LATENCY_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    private final KafkaAudioFramePublisher audioPublisher;
    private final ProcessAudioChunkUseCase processAudioChunkUseCase;
    private final ProgressConversationUseCase progressConversationUseCase;
    private final CallControlDispatcherPort callControlDispatcher;
    private final ConversationSessionRegistry sessionRegistry;
    private final MonotonicSequenceVerifier sequenceVerifier;
    private final KafkaListenerEndpointRegistry listenerRegistry;

    @MockitoBean private CustomerLookupPort customerLookupPort;
    @MockitoBean private DisruptionTicketPort disruptionTicketPort;
    @MockitoBean private OperatorRoutingPort operatorRoutingPort;
    @MockitoSpyBean private ConversationStatePublisherPort statePublisher;

    @Captor private ArgumentCaptor<CallStateTransitioned> stateTransitionCaptor;

    @Captor private ArgumentCaptor<TurnCompleted> turnCompletedCaptor;

    private Consumer<String, CallControlEnvelope> controlConsumer;
    private Consumer<String, AudioChunkEnvelope> rawAuditConsumer;
    private Consumer<String, TicketFormFilledEvent> ticketIntentConsumer;

    EndToEndConversationDialogIntegrationTest(
            @Autowired KafkaAudioFramePublisher audioPublisher,
            @Autowired ProcessAudioChunkUseCase processAudioChunkUseCase,
            @Autowired ProgressConversationUseCase progressConversationUseCase,
            @Autowired CallControlDispatcherPort callControlDispatcher,
            @Autowired ConversationSessionRegistry sessionRegistry,
            @Autowired MonotonicSequenceVerifier sequenceVerifier,
            @Autowired KafkaListenerEndpointRegistry listenerRegistry) {
        this.audioPublisher = audioPublisher;
        this.processAudioChunkUseCase = processAudioChunkUseCase;
        this.progressConversationUseCase = progressConversationUseCase;
        this.callControlDispatcher = callControlDispatcher;
        this.sessionRegistry = sessionRegistry;
        this.sequenceVerifier = sequenceVerifier;
        this.listenerRegistry = listenerRegistry;
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void setUp() {
        CustomerProfile profile =
                Instancio.of(CustomerProfile.class)
                        .set(field(CustomerProfile::pnr), new Pnr("A8F9X2"))
                        .set(field(CustomerProfile::customerName), "Andres")
                        .set(field(CustomerProfile::frequentFlyerTier), FrequentFlyerTier.STANDARD)
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
        when(customerLookupPort.lookupCustomerByPnr(new Pnr("A8F9X2")))
                .thenReturn(Optional.of(profile));

        when(disruptionTicketPort.finalizeTicket(any(AirlineDisruptionTicket.class)))
                .thenReturn(
                        Instancio.of(TicketSubmissionReceipt.class)
                                .set(field(TicketSubmissionReceipt::pnr), new Pnr("A8F9X2"))
                                .set(field(TicketSubmissionReceipt::status), "FINALIZED")
                                .create());

        when(operatorRoutingPort.transferToHumanOperator(
                        any(CallId.class), any(String.class), any(UUID.class)))
                .thenAnswer(
                        invocation ->
                                Instancio.of(OperatorHandoffReceipt.class)
                                        .set(
                                                field(OperatorHandoffReceipt::callId),
                                                invocation.getArgument(0))
                                        .set(
                                                field(OperatorHandoffReceipt::department),
                                                invocation.getArgument(1))
                                        .set(
                                                field(OperatorHandoffReceipt::ticketId),
                                                invocation.getArgument(2))
                                        .set(field(OperatorHandoffReceipt::status), "QUEUED")
                                        .create());

        MessageListenerContainer listenerContainer =
                listenerRegistry.getListenerContainer("audioStreamListener");
        if (listenerContainer != null) {
            ContainerTestUtils.waitForAssignment(listenerContainer, 12);
        }

        controlConsumer =
                createConsumer(
                        "e2e-control-audit-" + UUID.randomUUID(),
                        CallControlEnvelopeDeserializer.class,
                        CONTROL_TOPIC);
        rawAuditConsumer =
                createConsumer(
                        "e2e-raw-audit-" + UUID.randomUUID(),
                        AudioChunkEnvelopeDeserializer.class,
                        RAW_TOPIC);
        ticketIntentConsumer =
                createConsumer(
                        "e2e-ticket-audit-" + UUID.randomUUID(),
                        TicketFormFilledEventDeserializer.class,
                        TICKETS_INTENT_TOPIC);
    }

    @AfterEach
    void tearDown() {
        if (controlConsumer != null) {
            controlConsumer.close();
        }
        if (rawAuditConsumer != null) {
            rawAuditConsumer.close();
        }
        if (ticketIntentConsumer != null) {
            ticketIntentConsumer.close();
        }
    }

    @Test
    @DisplayName(
            "Complete SkyWay Airlines call lifecycle: Greeting -> PNR -> NATO confirmation ->"
                    + " Mid-stream Barge-In -> Ticket compilation & Kafka TicketFormFilledEvent")
    void fullSkyWayAirlinesDialogue_verifiesVadAcousticConfirmationBargeInAndTicketCompilation() {
        assertThat(processAudioChunkUseCase).isSameAs(progressConversationUseCase);
        assertThat(callControlDispatcher).isInstanceOf(KafkaCallControlDispatcher.class);

        CallId callId = CallId.generate();
        Instant t0 = Instant.parse("2026-11-10T15:00:00Z");
        long seq = 0L;

        // 1. Greeting prompt received (< 25 words)
        ConversationSession session = progressConversationUseCase.startConversation(callId, t0);
        assertThat(session.state()).isInstanceOf(CallSessionState.Greeting.class);
        assertThat(session.turnPhase()).isEqualTo(TurnPhase.BOT_SPEAKING);
        assertThat(session.lastBotPrompt()).isPresent();
        String greetingPrompt = session.lastBotPrompt().orElseThrow();
        assertThat(TurnBrevityValidator.isValid(greetingPrompt)).isTrue();
        assertThat(session.lastTurnLatencyNanos()).isLessThan(MAX_TURN_LATENCY_NANOS);

        session.completeBotTurn(t0.plusMillis(200));
        assertThat(session.turnPhase()).isEqualTo(TurnPhase.LISTENING);

        // 2. Caller utters PNR (10 speech frames + 25 silence frames = 500ms trailing silence)
        seq = streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 1);

        Instant t1 = t0.plusSeconds(2);
        progressConversationUseCase.progressTurn(
                callId, "Uh yes it is Alpha 8 Foxtrot 9 Xray 2", t1);

        assertThat(session.state())
                .isInstanceOf(CallSessionState.AwaitingBookingConfirmation.class)
                .satisfies(
                        s ->
                                assertThat(
                                                ((CallSessionState.AwaitingBookingConfirmation) s)
                                                        .transcribedPnr())
                                        .isEqualTo("A8F9X2"));
        String natoPrompt = session.lastBotPrompt().orElseThrow();
        assertThat(natoPrompt).isEqualTo("I heard Alpha-8-Foxtrot-9-Xray-2. Is that correct?");
        assertThat(TurnBrevityValidator.isValid(natoPrompt)).isTrue();
        assertThat(session.lastTurnLatencyNanos()).isLessThan(MAX_TURN_LATENCY_NANOS);
        assertThat(session.turnPhase()).isEqualTo(TurnPhase.BOT_SPEAKING);

        session.completeBotTurn(t1.plusMillis(200));

        // 3. Caller confirms ("Yes, that is correct") -> CRM lookup -> DisruptionTriage
        seq = streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 2);

        Instant t2 = t1.plusSeconds(2);
        progressConversationUseCase.progressTurn(callId, "Yes, that is correct", t2);

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
        String triagePrompt = session.lastBotPrompt().orElseThrow();
        assertThat(triagePrompt).contains("SW-104").contains("cancelled due to weather");
        assertThat(TurnBrevityValidator.isValid(triagePrompt)).isTrue();
        assertThat(session.lastTurnLatencyNanos()).isLessThan(MAX_TURN_LATENCY_NANOS);
        assertThat(session.turnPhase()).isEqualTo(TurnPhase.BOT_SPEAKING);

        // 4. Mid-stream barge-in: caller interrupts active bot playback with high-energy speech
        long bargeInSeq = seq++;
        byte[] bargeInFrame = SyntheticAudioTestFactory.generateSpeechBurstFrame((short) 14000);
        audioPublisher.publishAudioFrame(callId, bargeInSeq, bargeInFrame);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertThat(session.turnPhase()).isEqualTo(TurnPhase.LISTENING));

        ConsumerRecord<String, CallControlEnvelope> bargeInRecord =
                awaitBargeInControlRecord(callId);
        assertThat(bargeInRecord.key()).isEqualTo(callId.toString());
        assertThat(bargeInRecord.value()).isInstanceOf(BargeInInterrupt.class);
        BargeInInterrupt interrupt = (BargeInInterrupt) bargeInRecord.value();
        assertThat(interrupt.callId()).isEqualTo(callId);
        assertThat(interrupt.sequenceNumber()).isEqualTo(bargeInSeq);

        // 5. Caller completes barge-in utterance -> final preference capture, MCP & Kafka dispatch
        seq = streamSpeechAndTrailingSilence(callId, seq, 10, 25);
        awaitTurnBoundaryCount(callId, 3);

        Instant t3 = t2.plusSeconds(3);
        progressConversationUseCase.progressTurn(
                callId,
                "No wait, I need a hotel for tonight and the next available flight with wheelchair"
                        + " assistance",
                t3);

        assertThat(session.state())
                .isInstanceOf(CallSessionState.HandoffReady.class)
                .satisfies(
                        s -> {
                            CallSessionState.HandoffReady handoff =
                                    (CallSessionState.HandoffReady) s;
                            assertThat(handoff.targetSkillGroup())
                                    .isEqualTo("DISRUPTION_DESK_ACCESSIBILITY");
                        });
        String handoffPrompt = session.lastBotPrompt().orElseThrow();
        assertThat(handoffPrompt).contains("hotel request").contains("SW-104");
        assertThat(TurnBrevityValidator.isValid(handoffPrompt)).isTrue();
        assertThat(session.lastTurnLatencyNanos()).isLessThan(MAX_TURN_LATENCY_NANOS);

        assertThat(session.disruptionTicket()).isPresent();
        AirlineDisruptionTicket ticket = session.disruptionTicket().orElseThrow();
        assertThat(ticket.callId()).isEqualTo(callId.value());
        assertThat(ticket.passengerName()).isEqualTo("Andres");
        assertThat(ticket.pnr()).isEqualTo("A8F9X2");
        assertThat(ticket.originalFlightNumber()).isEqualTo("SW-104");
        assertThat(ticket.disruptionReason()).isEqualTo(DisruptionReason.CANCELED_WEATHER);
        assertThat(ticket.rebookingPreference()).isEqualTo(RebookingPreference.NEXT_AVAILABLE);
        assertThat(ticket.hotelRequired()).isTrue();
        assertThat(ticket.specialAssistance()).isTrue();
        assertThat(ticket.status()).isEqualTo("COMPILED");

        ConsumerRecord<String, TicketFormFilledEvent> ticketRecord =
                awaitTicketIntentRecord(callId);
        assertThat(ticketRecord.key()).isEqualTo(callId.toString());
        assertThat(ticketRecord.value().ticketId()).isEqualTo(ticket.ticketId());
        assertThat(ticketRecord.value().targetSkillGroup())
                .isEqualTo("DISRUPTION_DESK_ACCESSIBILITY");

        // Complete remaining state machine phases (Transferred -> Terminated)
        Instant t4 = t3.plusSeconds(1);
        session.transferToOperator("OP-PRIORITY-01", t4);
        assertThat(session.state()).isInstanceOf(CallSessionState.Transferred.class);

        Instant t5 = t4.plusSeconds(10);
        session.terminate("HANDOFF_COMPLETED", t5);
        assertThat(session.state()).isInstanceOf(CallSessionState.Terminated.class);
        assertThat(session.isTerminated()).isTrue();

        verify(statePublisher, atLeast(5)).publishStateTransition(stateTransitionCaptor.capture());
        verify(statePublisher, atLeast(3)).publishTurnCompleted(turnCompletedCaptor.capture());

        List<CallStateTransitioned> callTransitions =
                stateTransitionCaptor.getAllValues().stream()
                        .filter(e -> e.callId().equals(callId))
                        .toList();
        assertThat(callTransitions).hasSize(5);

        SequenceVerificationMetrics seqMetrics = sequenceVerifier.getMetrics();
        assertThat(seqMetrics.gapCount()).isZero();
        assertThat(seqMetrics.duplicateCount()).isZero();
        assertThat(seqMetrics.outOfOrderCount()).isZero();
        assertThat(seqMetrics.validFrames()).isGreaterThanOrEqualTo(seq);

        assertAllRawRecordsPartitionedByCallId(callId, (int) seq);
    }

    @Test
    @DisplayName(
            "Greeting barge-in and negative acoustic PNR confirmation retry recovers and compiles"
                    + " refund ticket")
    void greetingBargeInAndNegativeConfirmationRetry_recoversAndCompilesRefundTicket() {
        CallId callId = CallId.generate();
        Instant t0 = Instant.parse("2026-11-10T15:10:00Z");
        long seq = 0L;

        ConversationSession session = progressConversationUseCase.startConversation(callId, t0);
        assertThat(session.state()).isInstanceOf(CallSessionState.Greeting.class);
        assertThat(session.turnPhase()).isEqualTo(TurnPhase.BOT_SPEAKING);

        long greetingBargeInSeq = seq++;
        audioPublisher.publishAudioFrame(
                callId,
                greetingBargeInSeq,
                SyntheticAudioTestFactory.generateSpeechBurstFrame((short) 14000));

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            assertThat(session.turnPhase()).isEqualTo(TurnPhase.LISTENING);
                            assertThat(session.state())
                                    .isInstanceOf(
                                            CallSessionState.CollectingPassengerDetails.class);
                        });

        ConsumerRecord<String, CallControlEnvelope> bargeInRecord =
                awaitBargeInControlRecord(callId);
        assertThat(bargeInRecord.key()).isEqualTo(callId.toString());

        seq = streamSpeechAndTrailingSilence(callId, seq, 8, 25);
        awaitTurnBoundaryCount(callId, 1);

        Instant t1 = t0.plusSeconds(2);
        progressConversationUseCase.progressTurn(callId, "Bravo 7 Golf 8 Yankee 3", t1);
        assertThat(session.state())
                .isInstanceOf(CallSessionState.AwaitingBookingConfirmation.class);
        assertThat(session.lastBotPrompt())
                .contains("I heard Bravo-7-Golf-8-Yankee-3. Is that correct?");

        session.completeBotTurn(t1.plusMillis(200));
        seq = streamSpeechAndTrailingSilence(callId, seq, 8, 25);
        awaitTurnBoundaryCount(callId, 2);

        Instant t2 = t1.plusSeconds(2);
        progressConversationUseCase.progressTurn(callId, "No, that is wrong", t2);
        assertThat(session.state()).isInstanceOf(CallSessionState.CollectingPassengerDetails.class);
        assertThat(TurnBrevityValidator.isValid(session.lastBotPrompt().orElseThrow())).isTrue();

        session.completeBotTurn(t2.plusMillis(200));
        seq = streamSpeechAndTrailingSilence(callId, seq, 8, 25);
        awaitTurnBoundaryCount(callId, 3);

        Instant t3 = t2.plusSeconds(2);
        progressConversationUseCase.progressTurn(callId, "My reference is A8F9X2", t3);
        assertThat(session.state())
                .isInstanceOf(CallSessionState.AwaitingBookingConfirmation.class);
        assertThat(session.lastBotPrompt())
                .contains("I heard Alpha-8-Foxtrot-9-Xray-2. Is that correct?");

        session.completeBotTurn(t3.plusMillis(200));
        seq = streamSpeechAndTrailingSilence(callId, seq, 8, 25);
        awaitTurnBoundaryCount(callId, 4);

        Instant t4 = t3.plusSeconds(2);
        progressConversationUseCase.progressTurn(callId, "Yes", t4);
        assertThat(session.state()).isInstanceOf(CallSessionState.DisruptionTriage.class);

        session.completeBotTurn(t4.plusMillis(200));
        seq = streamSpeechAndTrailingSilence(callId, seq, 8, 25);
        awaitTurnBoundaryCount(callId, 5);

        Instant t5 = t4.plusSeconds(2);
        progressConversationUseCase.progressTurn(callId, "Please cancel and give me a refund", t5);
        assertThat(session.state()).isInstanceOf(CallSessionState.HandoffReady.class);
        assertThat(session.disruptionTicket()).isPresent();
        AirlineDisruptionTicket ticket = session.disruptionTicket().orElseThrow();
        assertThat(ticket.pnr()).isEqualTo("A8F9X2");
        assertThat(ticket.rebookingPreference()).isEqualTo(RebookingPreference.REFUND_REQUESTED);
        assertThat(ticket.hotelRequired()).isFalse();

        assertAllRawRecordsPartitionedByCallId(callId, (int) seq);
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
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            TrailingSilenceEvaluator evaluator =
                                    sessionRegistry.getOrCreateTrailingSilenceEvaluator(callId);
                            assertThat(evaluator.completedTurnCount())
                                    .isEqualTo(expectedTurnBoundaries);
                        });
    }

    private ConsumerRecord<String, CallControlEnvelope> awaitBargeInControlRecord(CallId callId) {
        List<ConsumerRecord<String, CallControlEnvelope>> matched = new ArrayList<>();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            ConsumerRecords<String, CallControlEnvelope> records =
                                    controlConsumer.poll(Duration.ofMillis(200));
                            for (ConsumerRecord<String, CallControlEnvelope> record : records) {
                                if (callId.toString().equals(record.key())) {
                                    matched.add(record);
                                }
                            }
                            assertThat(matched).isNotEmpty();
                        });
        return matched.getFirst();
    }

    private ConsumerRecord<String, TicketFormFilledEvent> awaitTicketIntentRecord(CallId callId) {
        List<ConsumerRecord<String, TicketFormFilledEvent>> matched = new ArrayList<>();
        await().atMost(Duration.ofSeconds(15))
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

    private void assertAllRawRecordsPartitionedByCallId(CallId callId, int expectedFrames) {
        List<ConsumerRecord<String, AudioChunkEnvelope>> matched = new ArrayList<>();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            ConsumerRecords<String, AudioChunkEnvelope> records =
                                    rawAuditConsumer.poll(Duration.ofMillis(200));
                            for (ConsumerRecord<String, AudioChunkEnvelope> record : records) {
                                if (record.value() != null
                                        && callId.equals(record.value().callId())) {
                                    assertThat(record.key()).isEqualTo(callId.toString());
                                    matched.add(record);
                                }
                            }
                            assertThat(matched).hasSize(expectedFrames);
                        });
    }

    private <V> Consumer<String, V> createConsumer(
            String groupId, Class<?> valueDeserializerClass, String topic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializerClass);

        DefaultKafkaConsumerFactory<String, V> factory = new DefaultKafkaConsumerFactory<>(props);
        Consumer<String, V> consumer = factory.createConsumer();
        consumer.subscribe(Collections.singletonList(topic));
        return consumer;
    }
}
