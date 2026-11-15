package com.voicepulse.routing.infrastructure.redis;

import com.voicepulse.routing.domain.model.OperatorId;
import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceReadSnapshot;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnection.BooleanResponse;
import org.springframework.data.redis.connection.ReactiveRedisConnection.CommandResponse;
import org.springframework.data.redis.connection.ReactiveRedisConnection.KeyCommand;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Pipelined reactive reader eliminating N+1 heartbeat {@code EXISTS} and presence {@code HGETALL}
 * round-trips when resolving single operators or skill-group membership lists.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "voicepulse.routing.redis",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class OperatorPresenceBatchReader {

    private final @NonNull ReactiveStringRedisTemplate stringRedisTemplate;
    private final @NonNull RedisKeySchema keySchema;

    /**
     * Reads heartbeat status and presence hash entries for all provided {@code operatorIds} using a
     * single pipelined {@link ReactiveRedisConnection}.
     *
     * @param operatorIds distinct or repeated operator IDs to inspect
     * @param loadExpiredHashes whether to still query {@code HGETALL} when heartbeat is absent
     *     (used on single lookups to distinguish unknown keys from expired presence hashes)
     * @return ordered list of immutable presence snapshots for the distinct operators
     */
    public Mono<List<OperatorPresenceReadSnapshot>> readOperatorPresences(
            List<OperatorId> operatorIds, boolean loadExpiredHashes) {
        Objects.requireNonNull(operatorIds, "operatorIds must not be null");
        if (operatorIds.isEmpty()) {
            return Mono.just(List.of());
        }

        List<OperatorId> uniqueIds = operatorIds.stream().distinct().toList();
        return stringRedisTemplate
                .execute(
                        connection ->
                                pipelinePresenceReads(connection, uniqueIds, loadExpiredHashes))
                .collectList()
                .map(List::copyOf);
    }

    private Flux<OperatorPresenceReadSnapshot> pipelinePresenceReads(
            ReactiveRedisConnection connection,
            List<OperatorId> uniqueIds,
            boolean loadExpiredHashes) {
        Map<String, OperatorId> heartbeatKeyIndex = new LinkedHashMap<>(uniqueIds.size());
        List<KeyCommand> heartbeatCommands = new ArrayList<>(uniqueIds.size());
        for (OperatorId operatorId : uniqueIds) {
            String heartbeatKey = keySchema.operatorHeartbeatKey(operatorId);
            heartbeatKeyIndex.put(heartbeatKey, operatorId);
            heartbeatCommands.add(new KeyCommand(encodeUtf8(heartbeatKey)));
        }

        return connection
                .keyCommands()
                .exists(Flux.fromIterable(heartbeatCommands))
                .collectList()
                .flatMapMany(
                        existsResponses ->
                                fetchHashesAfterHeartbeatCheck(
                                        connection,
                                        uniqueIds,
                                        heartbeatKeyIndex,
                                        existsResponses,
                                        loadExpiredHashes));
    }

    private Flux<OperatorPresenceReadSnapshot> fetchHashesAfterHeartbeatCheck(
            ReactiveRedisConnection connection,
            List<OperatorId> uniqueIds,
            Map<String, OperatorId> heartbeatKeyIndex,
            List<BooleanResponse<KeyCommand>> existsResponses,
            boolean loadExpiredHashes) {
        Map<OperatorId, Boolean> activeByOperatorId = new LinkedHashMap<>(uniqueIds.size());
        for (BooleanResponse<KeyCommand> response : existsResponses) {
            String heartbeatKey = decodeUtf8(response.getInput().getKey());
            OperatorId operatorId = heartbeatKeyIndex.get(heartbeatKey);
            if (operatorId != null) {
                activeByOperatorId.put(operatorId, Boolean.TRUE.equals(response.getOutput()));
            }
        }

        List<OperatorId> targets =
                loadExpiredHashes
                        ? uniqueIds
                        : uniqueIds.stream()
                                .filter(id -> Boolean.TRUE.equals(activeByOperatorId.get(id)))
                                .toList();

        if (targets.isEmpty()) {
            return Flux.fromIterable(buildSnapshots(uniqueIds, activeByOperatorId, Map.of()));
        }

        List<KeyCommand> hashCommands = new ArrayList<>(targets.size());
        for (OperatorId operatorId : targets) {
            hashCommands.add(new KeyCommand(encodeUtf8(keySchema.operatorPresenceKey(operatorId))));
        }

        return connection
                .hashCommands()
                .hGetAll(Flux.fromIterable(hashCommands))
                .flatMap(OperatorPresenceBatchReader::collectHashEntries)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .flatMapMany(
                        entriesByPresenceKey ->
                                Flux.fromIterable(
                                        buildSnapshots(
                                                uniqueIds,
                                                activeByOperatorId,
                                                entriesByPresenceKey)));
    }

    private static Mono<Map.Entry<String, Map<String, String>>> collectHashEntries(
            CommandResponse<KeyCommand, Flux<Map.Entry<ByteBuffer, ByteBuffer>>> response) {
        String presenceKey = decodeUtf8(response.getInput().getKey());
        Flux<Map.Entry<ByteBuffer, ByteBuffer>> entriesFlux = response.getOutput();
        if (entriesFlux == null) {
            return Mono.just(Map.entry(presenceKey, Map.of()));
        }
        return entriesFlux
                .collect(
                        LinkedHashMap<String, String>::new,
                        (map, entry) ->
                                map.put(decodeUtf8(entry.getKey()), decodeUtf8(entry.getValue())))
                .map(entries -> Map.entry(presenceKey, Collections.unmodifiableMap(entries)));
    }

    private List<OperatorPresenceReadSnapshot> buildSnapshots(
            List<OperatorId> uniqueIds,
            Map<OperatorId, Boolean> activeByOperatorId,
            Map<String, Map<String, String>> entriesByPresenceKey) {
        List<OperatorPresenceReadSnapshot> snapshots = new ArrayList<>(uniqueIds.size());
        for (OperatorId operatorId : uniqueIds) {
            boolean active = Boolean.TRUE.equals(activeByOperatorId.get(operatorId));
            Map<String, String> hashEntries =
                    entriesByPresenceKey.getOrDefault(
                            keySchema.operatorPresenceKey(operatorId), Map.of());
            snapshots.add(new OperatorPresenceReadSnapshot(operatorId, active, hashEntries));
        }
        return snapshots;
    }

    private static ByteBuffer encodeUtf8(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeUtf8(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return "";
        }
        ByteBuffer readOnly = buffer.asReadOnlyBuffer();
        byte[] bytes = new byte[readOnly.remaining()];
        readOnly.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
