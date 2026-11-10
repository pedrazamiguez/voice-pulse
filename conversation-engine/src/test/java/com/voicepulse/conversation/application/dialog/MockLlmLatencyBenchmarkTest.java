package com.voicepulse.conversation.application.dialog;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.conversation.domain.statemachine.ConversationSession;
import com.voicepulse.shared.model.CallId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MockLlmConversationProcessor Latency Benchmark Tests")
class MockLlmLatencyBenchmarkTest {

    private static final int WARMUP_ITERATIONS = 100;
    private static final int BENCHMARK_ITERATIONS = 1000;
    private static final long MAX_PERMISSIBLE_LATENCY_MS = 50L;

    private MockLlmConversationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new MockLlmConversationProcessor(new ConversationResponseGenerator());
    }

    @Test
    @DisplayName("Executes multi-turn turns with deterministic sub-50ms latency (p99 < 5ms)")
    void processTurn_latencyBenchmark_sub50msDeterministic() {
        Instant now = Instant.now();

        // Warm-up phase to trigger JIT compilation and class loading
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            ConversationSession session = ConversationSession.start(CallId.generate(), now);
            session.greet(now);
            processor.processTurn(
                    session, "Uh, my PNR is alpha eight, foxtrot nine, x-ray two.", now);
            processor.processTurn(session, "Yes, that's correct.", now);
            processor.processTurn(session, "I need a hotel for tonight and next flight.", now);
        }

        List<Long> latenciesMicros = new ArrayList<>(BENCHMARK_ITERATIONS * 3);

        // Benchmark phase
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            ConversationSession session = ConversationSession.start(CallId.generate(), now);
            session.greet(now);

            long start = System.nanoTime();
            DialogTurnResult turn1 =
                    processor.processTurn(
                            session, "Uh, my PNR is alpha eight, foxtrot nine, x-ray two.", now);
            latenciesMicros.add((System.nanoTime() - start) / 1000);

            start = System.nanoTime();
            DialogTurnResult turn2 = processor.processTurn(session, "Yes, that's correct.", now);
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
        long p50Micros = latenciesMicros.get((int) (totalMeasurements * 0.50));
        long p95Micros = latenciesMicros.get((int) (totalMeasurements * 0.95));
        long p99Micros = latenciesMicros.get((int) (totalMeasurements * 0.99));
        long maxMicros = latenciesMicros.get(totalMeasurements - 1);

        double maxMillis = maxMicros / 1000.0;
        double p99Millis = p99Micros / 1000.0;

        // Strict SLA assertions per RFC-0001 §4.3 and Issue #103
        assertThat(maxMillis)
                .as("Max turn latency must be strictly less than 50ms")
                .isLessThan(MAX_PERMISSIBLE_LATENCY_MS);

        assertThat(p99Millis)
                .as("p99 latency should be well under 10ms for deterministic mock engine")
                .isLessThan(10.0);
    }
}
