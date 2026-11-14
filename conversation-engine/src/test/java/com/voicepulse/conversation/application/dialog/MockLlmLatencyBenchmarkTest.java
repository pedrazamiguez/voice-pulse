package com.voicepulse.conversation.application.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

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
import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.DisruptionReason;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MockLlmConversationProcessor Latency Benchmark Tests")
class MockLlmLatencyBenchmarkTest {

    private static final int WARMUP_ITERATIONS = 100;
    private static final int BENCHMARK_ITERATIONS = 1000;
    private static final long MAX_PERMISSIBLE_LATENCY_MS = 50L;

    @Mock private CustomerLookupPort customerLookupPort;
    @Mock private DisruptionTicketPort disruptionTicketPort;
    @Mock private OperatorRoutingPort operatorRoutingPort;
    @Mock private TicketIntentPublisherPort ticketIntentPublisherPort;

    private MockLlmConversationProcessor processor;

    @BeforeEach
    void setUp() {
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
                .when(customerLookupPort.lookupCustomerByPnr(any(Pnr.class)))
                .thenReturn(Optional.of(profile));

        TicketSubmissionReceipt submissionReceipt =
                Instancio.of(TicketSubmissionReceipt.class)
                        .set(field(TicketSubmissionReceipt::pnr), new Pnr("A8F9X2"))
                        .create();
        lenient()
                .when(disruptionTicketPort.finalizeTicket(any(AirlineDisruptionTicket.class)))
                .thenReturn(submissionReceipt);

        OperatorHandoffReceipt handoffReceipt = Instancio.create(OperatorHandoffReceipt.class);
        lenient()
                .when(
                        operatorRoutingPort.transferToHumanOperator(
                                any(CallId.class), any(String.class), any(UUID.class)))
                .thenReturn(handoffReceipt);

        DisruptionTriageCoordinator triageCoordinator =
                new DisruptionTriageCoordinator(
                        customerLookupPort,
                        disruptionTicketPort,
                        operatorRoutingPort,
                        ticketIntentPublisherPort,
                        new SkillGroupRoutingPolicy());
        processor =
                new MockLlmConversationProcessor(
                        new ConversationResponseGenerator(), triageCoordinator);
    }

    @Test
    @DisplayName("Executes multi-turn turns with deterministic sub-50ms latency (p99 < 10ms)")
    void processTurn_latencyBenchmark_sub50msDeterministic() {
        Instant now = Instant.now();

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            ConversationSession session = ConversationSession.start(CallId.generate(), now);
            session.greet(now);
            processor.processTurn(
                    session, "Uh, my PNR is alpha eight, foxtrot nine, x-ray two.", now);
            processor.processTurn(session, "Yes, that's correct.", now);
            processor.processTurn(session, "I need a hotel for tonight and next flight.", now);
        }

        List<Long> latenciesMicros = new ArrayList<>(BENCHMARK_ITERATIONS * 3);

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            ConversationSession session = ConversationSession.start(CallId.generate(), now);
            session.greet(now);

            long start = System.nanoTime();
            processor.processTurn(
                    session, "Uh, my PNR is alpha eight, foxtrot nine, x-ray two.", now);
            latenciesMicros.add((System.nanoTime() - start) / 1000);

            start = System.nanoTime();
            processor.processTurn(session, "Yes, that's correct.", now);
            latenciesMicros.add((System.nanoTime() - start) / 1000);

            start = System.nanoTime();
            DialogTurnResult turn3 =
                    processor.processTurn(
                            session, "I need a hotel for tonight and next flight.", now);
            latenciesMicros.add((System.nanoTime() - start) / 1000);

            assertThat(turn3.disruptionTicket()).isPresent();
        }

        Collections.sort(latenciesMicros);

        int totalMeasurements = latenciesMicros.size();
        long p99Micros = latenciesMicros.get((int) (totalMeasurements * 0.99));
        long maxMicros = latenciesMicros.get(totalMeasurements - 1);

        double maxMillis = maxMicros / 1000.0;
        double p99Millis = p99Micros / 1000.0;

        assertThat(maxMillis)
                .as("Max turn latency must be strictly less than 50ms")
                .isLessThan(MAX_PERMISSIBLE_LATENCY_MS);

        assertThat(p99Millis)
                .as("p99 latency should be well under 10ms for deterministic mock engine")
                .isLessThan(10.0);
    }
}
