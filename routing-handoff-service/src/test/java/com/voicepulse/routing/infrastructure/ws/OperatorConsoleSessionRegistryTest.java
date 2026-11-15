package com.voicepulse.routing.infrastructure.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.infrastructure.ws.config.OperatorConsoleProperties;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorAssignmentPayloadDto;
import com.voicepulse.routing.infrastructure.ws.dto.OperatorWsMessage;
import com.voicepulse.shared.model.CallId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@DisplayName("OperatorConsoleSessionRegistry Tests")
class OperatorConsoleSessionRegistryTest {

    private static final Instant NOW = Instant.parse("2026-11-15T09:00:00Z");

    private OperatorConsoleSessionRegistry registry;

    @BeforeEach
    void setUp() {
        OperatorConsoleProperties properties =
                new OperatorConsoleProperties("ws://localhost:8080", Duration.ofSeconds(10), 64);
        registry = new OperatorConsoleSessionRegistry(properties);
    }

    @Test
    @DisplayName(
            "Registers multiple connections per operator, delivers messages, and unregisters"
                    + " cleanly")
    void managesConnectionsAndDelivery() {
        OperatorId op1 = OperatorId.fromString("op-1");
        OperatorId op2 = OperatorId.fromString("op-2");

        Flux<OperatorWsMessage> flux1 = registry.register(op1, "conn-1");
        Flux<OperatorWsMessage> flux2 = registry.register(op2, "conn-2");

        assertThat(registry.activeConnectionCount(op1)).isEqualTo(1);
        assertThat(registry.totalActiveConnections()).isEqualTo(2);

        OperatorWsMessage pong = new OperatorWsMessage.PongMessage("op-1", NOW);
        OperatorWsMessage metrics =
                new OperatorWsMessage.QueueMetricsMessage(
                        "DISRUPTION_DESK_GOLD", 2L, Map.of("DISRUPTION_DESK_GOLD", 2L), NOW);

        StepVerifier.create(flux1.take(2))
                .then(() -> registry.sendToConnection(op1, "conn-1", pong))
                .then(() -> registry.broadcast(metrics))
                .expectNext(pong, metrics)
                .verifyComplete();

        StepVerifier.create(flux2.take(1))
                .then(() -> registry.broadcast(metrics))
                .expectNext(metrics)
                .verifyComplete();

        registry.unregister(op1, "conn-1");
        registry.unregister(op2, "conn-2");

        assertThat(registry.activeConnectionCount(op1)).isZero();
        assertThat(registry.totalActiveConnections()).isZero();
    }

    @Test
    @DisplayName("Caches and selectively evicts active assignment payloads by CallId")
    void cachesAndEvictsAssignments() {
        OperatorId op1 = OperatorId.fromString("op-1");
        CallId callId1 = CallId.generate();
        CallId callId2 = CallId.generate();

        OperatorAssignmentPayloadDto payload =
                Instancio.of(OperatorAssignmentPayloadDto.class)
                        .set(field(OperatorAssignmentPayloadDto::callId), callId1.toString())
                        .set(field(OperatorAssignmentPayloadDto::operatorId), "op-1")
                        .set(field(OperatorAssignmentPayloadDto::transcript), List.of())
                        .set(field(OperatorAssignmentPayloadDto::metadata), Map.of())
                        .create();

        registry.cacheActiveAssignment(op1, payload);
        assertThat(registry.getActiveAssignment(op1)).contains(payload);

        // Non-matching callId should not evict
        registry.evictActiveAssignment(op1, callId2);
        assertThat(registry.getActiveAssignment(op1)).contains(payload);

        // Matching callId evicts
        registry.evictActiveAssignment(op1, callId1);
        assertThat(registry.getActiveAssignment(op1)).isEmpty();

        // Global eviction by callId
        registry.cacheActiveAssignment(op1, payload);
        registry.evictActiveAssignmentByCallId(callId1);
        assertThat(registry.getActiveAssignment(op1)).isEmpty();
    }
}
