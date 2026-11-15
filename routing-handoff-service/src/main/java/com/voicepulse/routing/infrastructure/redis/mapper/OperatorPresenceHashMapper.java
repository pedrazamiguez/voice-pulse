package com.voicepulse.routing.infrastructure.redis.mapper;

import com.voicepulse.routing.infrastructure.redis.model.OperatorPresenceHash;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mapping.model.MappingInstantiationException;
import org.springframework.data.redis.core.convert.Bucket;
import org.springframework.data.redis.core.convert.DefaultRedisTypeMapper;
import org.springframework.data.redis.core.convert.MappingRedisConverter;
import org.springframework.data.redis.core.convert.RedisData;
import org.springframework.data.redis.core.mapping.RedisMappingContext;
import org.springframework.data.redis.hash.HashMapper;
import org.springframework.stereotype.Component;

/**
 * Spring Data {@link HashMapper} translating flat Redis String Hash entries to and from {@link
 * OperatorPresenceHash} records via {@link MappingRedisConverter} without emitting {@code @class}
 * or {@code _class} type-hint keys.
 */
@Slf4j
@Component
public class OperatorPresenceHashMapper
        implements HashMapper<OperatorPresenceHash, String, String> {

    private final MappingRedisConverter converter;

    public OperatorPresenceHashMapper() {
        RedisMappingContext mappingContext = new RedisMappingContext();
        mappingContext.afterPropertiesSet();
        DefaultRedisTypeMapper typeMapper = new DefaultRedisTypeMapper(null, mappingContext);
        MappingRedisConverter redisConverter =
                new MappingRedisConverter(mappingContext, null, null, typeMapper);
        redisConverter.afterPropertiesSet();
        this.converter = redisConverter;
    }

    @Override
    public Map<String, String> toHash(OperatorPresenceHash object) {
        Objects.requireNonNull(object, "object must not be null");
        RedisData redisData = new RedisData();
        converter.write(object, redisData);
        Map<byte[], byte[]> rawMap = redisData.getBucket().rawMap();
        Map<String, String> result = new LinkedHashMap<>(rawMap.size());
        for (Map.Entry<byte[], byte[]> entry : rawMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(
                        new String(entry.getKey(), StandardCharsets.UTF_8),
                        new String(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public OperatorPresenceHash fromHash(Map<String, String> hash) {
        if (hash == null || hash.isEmpty()) {
            throw new IllegalArgumentException("Redis hash entries must not be null or empty");
        }
        Bucket bucket = Bucket.newBucketFromStringMap(hash);
        return converter.read(OperatorPresenceHash.class, new RedisData(bucket));
    }

    /**
     * Safely decodes Redis hash entries into an {@link OperatorPresenceHash}, returning {@link
     * Optional#empty()} when the hash is absent or missing required non-blank attributes.
     */
    public Optional<OperatorPresenceHash> tryFromHash(Map<String, String> hash) {
        if (hash == null || hash.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(fromHash(hash));
        } catch (MappingException | MappingInstantiationException | IllegalArgumentException ex) {
            log.warn(
                    "Ignoring incomplete or invalid OperatorPresenceHash entries: keys={},"
                            + " reason={}",
                    hash.keySet(),
                    ex.getMessage());
            return Optional.empty();
        }
    }
}
