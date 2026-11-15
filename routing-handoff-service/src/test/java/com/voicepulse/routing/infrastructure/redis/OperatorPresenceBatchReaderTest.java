package com.voicepulse.routing.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.infrastructure.redis.config.RedisRoutingProperties;
import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceReadSnapshot;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Publisher;
import org.springframework.data.redis.connection.ReactiveHashCommands;
import org.springframework.data.redis.connection.ReactiveKeyCommands;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnection.BooleanResponse;
import org.springframework.data.redis.connection.ReactiveRedisConnection.CommandResponse;
import org.springframework.data.redis.connection.ReactiveRedisConnection.KeyCommand;
import org.springframework.data.redis.core.ReactiveRedisCallback;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("OperatorPresenceBatchReader Unit Tests")
class OperatorPresenceBatchReaderTest {

    @Mock private ReactiveStringRedisTemplate stringRedisTemplate;
    @Mock private ReactiveRedisConnection connection;
    @Mock private ReactiveKeyCommands keyCommands;
    @Mock private ReactiveHashCommands hashCommands;

    private RedisKeySchema keySchema;
    private OperatorPresenceBatchReader batchReader;

    @BeforeEach
    void setUp() {
        RedisRoutingProperties redisProps =
                new RedisRoutingProperties(true, "voicepulse:routing", Duration.ofSeconds(5));
        keySchema = new RedisKeySchema(redisProps);
        batchReader = new OperatorPresenceBatchReader(stringRedisTemplate, keySchema);
    }

    @Test
    @DisplayName("Should short-circuit empty operator list without opening a Redis connection")
    void shouldShortCircuitEmptyOperatorIds() {
        StepVerifier.create(batchReader.readOperatorPresences(List.of(), false))
                .expectNext(List.of())
                .verifyComplete();

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName(
            "Should skip HGETALL for expired operators in batch mode when loadExpiredHashes is"
                    + " false")
    void shouldPipelineExistsAndSkipExpiredHashFetchesInBatchMode() {
        OperatorId activeOp = OperatorId.fromString("op-active-1");
        OperatorId expiredOp = OperatorId.fromString("op-expired-1");

        stubPipelineConnection();

        when(keyCommands.exists(any(Publisher.class)))
                .thenAnswer(
                        invocation -> {
                            Publisher<KeyCommand> commands = invocation.getArgument(0);
                            return Flux.from(commands)
                                    .map(
                                            cmd -> {
                                                String key = decode(cmd.getKey());
                                                boolean exists =
                                                        key.equals(
                                                                keySchema.operatorHeartbeatKey(
                                                                        activeOp));
                                                return new BooleanResponse<>(cmd, exists);
                                            });
                        });

        List<String> requestedHashKeys = new ArrayList<>();
        when(hashCommands.hGetAll(any(Publisher.class)))
                .thenAnswer(
                        invocation -> {
                            Publisher<KeyCommand> commands = invocation.getArgument(0);
                            return Flux.from(commands)
                                    .map(
                                            cmd -> {
                                                requestedHashKeys.add(decode(cmd.getKey()));
                                                return new CommandResponse<>(
                                                        cmd,
                                                        Flux.just(
                                                                Map.entry(
                                                                        utf8("operatorId"),
                                                                        utf8("op-active-1")),
                                                                Map.entry(
                                                                        utf8("status"),
                                                                        utf8("AVAILABLE"))));
                                            });
                        });

        StepVerifier.create(
                        batchReader.readOperatorPresences(
                                List.of(activeOp, expiredOp, activeOp), false))
                .assertNext(
                        snapshots -> {
                            assertThat(snapshots).hasSize(2);
                            assertThat(snapshots.get(0).operatorId()).isEqualTo(activeOp);
                            assertThat(snapshots.get(0).heartbeatActive()).isTrue();
                            assertThat(snapshots.get(0).hashEntries())
                                    .containsEntry("operatorId", "op-active-1")
                                    .containsEntry("status", "AVAILABLE");

                            assertThat(snapshots.get(1).operatorId()).isEqualTo(expiredOp);
                            assertThat(snapshots.get(1).heartbeatActive()).isFalse();
                            assertThat(snapshots.get(1).hashEntries()).isEmpty();
                        })
                .verifyComplete();

        assertThat(requestedHashKeys).containsExactly(keySchema.operatorPresenceKey(activeOp));
    }

    @Test
    @DisplayName(
            "Should still inspect presence hash when heartbeat expired if loadExpiredHashes is"
                    + " true")
    void shouldFetchHashEvenWhenExpiredIfLoadExpiredHashesTrue() {
        OperatorId expiredWithLeftover = OperatorId.fromString("op-stale-hash");
        stubPipelineConnection();

        when(keyCommands.exists(any(Publisher.class)))
                .thenAnswer(
                        invocation -> {
                            Publisher<KeyCommand> commands = invocation.getArgument(0);
                            return Flux.from(commands)
                                    .map(cmd -> new BooleanResponse<>(cmd, false));
                        });

        when(hashCommands.hGetAll(any(Publisher.class)))
                .thenAnswer(
                        invocation -> {
                            Publisher<KeyCommand> commands = invocation.getArgument(0);
                            return Flux.from(commands)
                                    .map(
                                            cmd ->
                                                    new CommandResponse<>(
                                                            cmd,
                                                            Flux.just(
                                                                    Map.entry(
                                                                            utf8("operatorId"),
                                                                            utf8(
                                                                                    "op-stale-hash")))));
                        });

        StepVerifier.create(batchReader.readOperatorPresences(List.of(expiredWithLeftover), true))
                .assertNext(
                        snapshots -> {
                            assertThat(snapshots).hasSize(1);
                            OperatorPresenceReadSnapshot snapshot = snapshots.getFirst();
                            assertThat(snapshot.heartbeatActive()).isFalse();
                            assertThat(snapshot.hashEntries())
                                    .containsEntry("operatorId", "op-stale-hash");
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "Should avoid calling hGetAll when all candidates are expired and loadExpiredHashes is"
                    + " false")
    void shouldAvoidHashCommandWhenAllCandidatesExpiredInBatchMode() {
        OperatorId expiredOp = OperatorId.fromString("op-all-expired");
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(stringRedisTemplate.execute(any(ReactiveRedisCallback.class)))
                .thenAnswer(
                        invocation -> {
                            ReactiveRedisCallback<OperatorPresenceReadSnapshot> callback =
                                    invocation.getArgument(0);
                            return Flux.from(callback.doInRedis(connection));
                        });
        when(keyCommands.exists(any(Publisher.class)))
                .thenAnswer(
                        invocation -> {
                            Publisher<KeyCommand> commands = invocation.getArgument(0);
                            return Flux.from(commands)
                                    .map(cmd -> new BooleanResponse<>(cmd, false));
                        });

        StepVerifier.create(batchReader.readOperatorPresences(List.of(expiredOp), false))
                .assertNext(
                        snapshots -> {
                            assertThat(snapshots).hasSize(1);
                            assertThat(snapshots.getFirst().heartbeatActive()).isFalse();
                            assertThat(snapshots.getFirst().hashEntries()).isEmpty();
                        })
                .verifyComplete();

        verify(connection, never()).hashCommands();
    }

    @SuppressWarnings("unchecked")
    private void stubPipelineConnection() {
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(connection.hashCommands()).thenReturn(hashCommands);
        when(stringRedisTemplate.execute(any(ReactiveRedisCallback.class)))
                .thenAnswer(
                        invocation -> {
                            ReactiveRedisCallback<OperatorPresenceReadSnapshot> callback =
                                    invocation.getArgument(0);
                            return Flux.from(callback.doInRedis(connection));
                        });
    }

    private static ByteBuffer utf8(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(ByteBuffer buffer) {
        ByteBuffer copy = buffer.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
