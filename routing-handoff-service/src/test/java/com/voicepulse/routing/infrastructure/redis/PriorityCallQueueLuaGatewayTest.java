package com.voicepulse.routing.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.routing.domain.model.QueuedTicketItem;
import com.voicepulse.routing.domain.model.SkillGroup;
import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import com.voicepulse.routing.infrastructure.redis.config.RedisRoutingProperties;
import com.voicepulse.routing.infrastructure.redis.mapper.RedisRoutingMapper;
import com.voicepulse.routing.infrastructure.redis.model.QueuedTicketRedisPayload;
import com.voicepulse.shared.event.TicketFormFilledEvent;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import com.voicepulse.shared.model.CallId;
import com.voicepulse.shared.model.FlightNumber;
import com.voicepulse.shared.model.Pnr;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisElementReader;
import org.springframework.data.redis.serializer.RedisElementWriter;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("PriorityCallQueueLuaGateway Unit Tests")
class PriorityCallQueueLuaGatewayTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:10:00Z");

    @Mock private ReactiveStringRedisTemplate stringRedisTemplate;

    @Mock private ReactiveRedisTemplate<String, QueuedTicketRedisPayload> queuedTicketRedisTemplate;

    @Captor private ArgumentCaptor<List<String>> argsCaptor;

    private ObjectMapper objectMapper;
    private RedisKeySchema keySchema;
    private RedisLuaScripts luaScripts;
    private RedisRoutingMapper mapper;
    private RedisSerializationContext<String, QueuedTicketRedisPayload> serializationContext;
    private PriorityCallQueueLuaGateway gateway;

    @BeforeEach
    void setUp() {
        RedisRoutingProperties redisProps =
                new RedisRoutingProperties(true, "voicepulse:routing", Duration.ofSeconds(5));
        RoutingProperties routingProps =
                new RoutingProperties(
                        "telephony.tickets.intent",
                        "telephony.calls.control",
                        "routing-handoff-workers",
                        Duration.ofSeconds(30),
                        "/ws/operators",
                        100);
        objectMapper = KafkaJsonCodecProvider.objectMapper();
        keySchema = new RedisKeySchema(redisProps);
        luaScripts = new RedisLuaScripts();
        mapper = Mappers.getMapper(RedisRoutingMapper.class);

        Jackson2JsonRedisSerializer<QueuedTicketRedisPayload> serializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, QueuedTicketRedisPayload.class);
        serializationContext =
                RedisSerializationContext.<String, QueuedTicketRedisPayload>newSerializationContext(
                                StringRedisSerializer.UTF_8)
                        .value(serializer)
                        .hashValue(serializer)
                        .build();
        when(queuedTicketRedisTemplate.getSerializationContext()).thenReturn(serializationContext);

        gateway =
                new PriorityCallQueueLuaGateway(
                        stringRedisTemplate,
                        queuedTicketRedisTemplate,
                        keySchema,
                        luaScripts,
                        routingProps);
    }

    private QueuedTicketRedisPayload samplePayload(SkillGroup skillGroup, int priorityScore) {
        TicketFormFilledEvent event =
                Instancio.of(TicketFormFilledEvent.class)
                        .set(field(TicketFormFilledEvent::pnr), new Pnr("A8F9X2"))
                        .set(
                                field(TicketFormFilledEvent::originalFlightNumber),
                                new FlightNumber("SW-104"))
                        .set(field(TicketFormFilledEvent::targetSkillGroup), skillGroup.name())
                        .set(field(TicketFormFilledEvent::payload), Map.of("hotel_rooms", 1))
                        .set(field(TicketFormFilledEvent::timestamp), NOW)
                        .create();
        QueuedTicketItem item = new QueuedTicketItem(event, skillGroup, priorityScore, NOW);
        return mapper.toRedisPayload(item);
    }

    @Test
    @DisplayName(
            "Should encode JSON ARGV[5] via RedisSerializationContext and keep numeric/string args"
                    + " unquoted")
    void shouldEncodePayloadViaSerializationContextDuringEnqueue() throws Exception {
        QueuedTicketRedisPayload payload = samplePayload(SkillGroup.DISRUPTION_DESK_GOLD, 100);
        CallId callId = CallId.fromString(payload.callId());

        when(stringRedisTemplate.execute(
                        eq(luaScripts.enqueuePriorityCallScript()), anyList(), anyList()))
                .thenReturn(Flux.just(2L));

        StepVerifier.create(
                        gateway.enqueue(payload, callId, SkillGroup.DISRUPTION_DESK_GOLD, 100, NOW))
                .expectNext(2)
                .verifyComplete();

        verify(stringRedisTemplate)
                .execute(
                        eq(luaScripts.enqueuePriorityCallScript()),
                        anyList(),
                        argsCaptor.capture());

        List<String> capturedArgs = argsCaptor.getValue();
        assertThat(capturedArgs.get(0)).isEqualTo(callId.toString());
        assertThat(capturedArgs.get(1)).isEqualTo("DISRUPTION_DESK_GOLD");
        assertThat(capturedArgs.get(2)).isEqualTo("-100");
        assertThat(capturedArgs.get(5)).isEqualTo("100");
        QueuedTicketRedisPayload decoded =
                objectMapper.readValue(capturedArgs.get(4), QueuedTicketRedisPayload.class);
        assertThat(decoded).isEqualTo(payload);
    }

    @Test
    @DisplayName("Should fail cleanly when queue capacity exceeded or Lua returns empty")
    void shouldErrorOnCapacityExceededOrEmptyEnqueueResult() {
        QueuedTicketRedisPayload payload = samplePayload(SkillGroup.DISRUPTION_DESK_STANDARD, 50);
        CallId callId = CallId.fromString(payload.callId());

        when(stringRedisTemplate.execute(
                        eq(luaScripts.enqueuePriorityCallScript()), anyList(), anyList()))
                .thenReturn(Flux.just(-1L));

        StepVerifier.create(
                        gateway.enqueue(
                                payload, callId, SkillGroup.DISRUPTION_DESK_STANDARD, 50, NOW))
                .expectErrorMatches(
                        err ->
                                err instanceof RedisRoutingException
                                        && err.getMessage().contains("capacity exceeded"))
                .verify();

        when(stringRedisTemplate.execute(
                        eq(luaScripts.enqueuePriorityCallScript()), anyList(), anyList()))
                .thenReturn(Flux.empty());

        StepVerifier.create(
                        gateway.enqueue(
                                payload, callId, SkillGroup.DISRUPTION_DESK_STANDARD, 50, NOW))
                .expectErrorMatches(
                        err ->
                                err instanceof RedisRoutingException
                                        && err.getMessage().contains("returned no result"))
                .verify();
    }

    @Test
    @DisplayName("Should wrap SerializationException thrown while encoding payload for enqueue")
    void shouldWrapSerializationExceptionDuringEnqueue() {
        QueuedTicketRedisPayload payload = samplePayload(SkillGroup.DISRUPTION_DESK_GOLD, 90);
        CallId callId = CallId.fromString(payload.callId());

        @SuppressWarnings("unchecked")
        RedisSerializationContext<String, QueuedTicketRedisPayload> failingContext =
                mock(RedisSerializationContext.class);
        @SuppressWarnings("unchecked")
        RedisSerializationContext.SerializationPair<QueuedTicketRedisPayload> failingPair =
                mock(RedisSerializationContext.SerializationPair.class);
        when(failingPair.write(payload))
                .thenThrow(new SerializationException("cannot serialize payload"));
        when(failingContext.getValueSerializationPair()).thenReturn(failingPair);
        when(queuedTicketRedisTemplate.getSerializationContext()).thenReturn(failingContext);

        assertThatThrownBy(
                        () ->
                                gateway.enqueue(
                                        payload, callId, SkillGroup.DISRUPTION_DESK_GOLD, 90, NOW))
                .isInstanceOf(RedisRoutingException.class)
                .hasMessageContaining("Failed to serialize QueuedTicketRedisPayload");
    }

    @Test
    @DisplayName("Should execute dequeue and remove scripts with typed reader/writer pairs")
    @SuppressWarnings("unchecked")
    void shouldExecuteDequeueAndRemoveScriptsWithTypedReaderWriter() {
        QueuedTicketRedisPayload payload = samplePayload(SkillGroup.DISRUPTION_DESK_PRIORITY, 80);

        when(queuedTicketRedisTemplate.execute(
                        eq(luaScripts.dequeuePriorityCallScript()),
                        anyList(),
                        eq(List.of()),
                        any(RedisElementWriter.class),
                        any(RedisElementReader.class)))
                .thenReturn(Flux.just(payload));

        when(queuedTicketRedisTemplate.execute(
                        eq(luaScripts.removePriorityCallScript()),
                        anyList(),
                        anyList(),
                        any(RedisElementWriter.class),
                        any(RedisElementReader.class)))
                .thenReturn(Flux.empty());

        StepVerifier.create(gateway.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_PRIORITY))
                .expectNext(Optional.of(payload))
                .verifyComplete();

        StepVerifier.create(gateway.removeByCallId(CallId.generate()))
                .expectNext(Optional.empty())
                .verifyComplete();
    }

    @Test
    @DisplayName("Should translate deserialization errors during dequeue and remove scripts")
    @SuppressWarnings("unchecked")
    void shouldTranslateDeserializationErrors() {
        when(queuedTicketRedisTemplate.execute(
                        eq(luaScripts.dequeuePriorityCallScript()),
                        anyList(),
                        anyList(),
                        any(RedisElementWriter.class),
                        any(RedisElementReader.class)))
                .thenReturn(Flux.error(new SerializationException("corrupt JSON")));

        when(queuedTicketRedisTemplate.execute(
                        eq(luaScripts.removePriorityCallScript()),
                        anyList(),
                        anyList(),
                        any(RedisElementWriter.class),
                        any(RedisElementReader.class)))
                .thenReturn(Flux.error(new SerializationException("corrupt JSON")));

        StepVerifier.create(gateway.dequeueHighestPriority(SkillGroup.DISRUPTION_DESK_GOLD))
                .expectError(RedisRoutingException.class)
                .verify();

        StepVerifier.create(gateway.removeByCallId(CallId.generate()))
                .expectError(RedisRoutingException.class)
                .verify();
    }
}
