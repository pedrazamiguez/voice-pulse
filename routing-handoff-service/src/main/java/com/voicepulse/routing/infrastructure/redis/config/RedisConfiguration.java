package com.voicepulse.routing.infrastructure.redis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicepulse.routing.infrastructure.redis.model.QueuedTicketRedisPayload;
import com.voicepulse.shared.infrastructure.kafka.serializer.KafkaJsonCodecProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Registers {@link RedisRoutingProperties} and typed Spring Data Redis serialization codecs for
 * queued call ticket payloads.
 */
@Configuration
@EnableConfigurationProperties(RedisRoutingProperties.class)
public class RedisConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "voicepulse.routing.redis",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    RedisSerializer<QueuedTicketRedisPayload> queuedTicketRedisSerializer(
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper =
                objectMapperProvider.getIfAvailable(KafkaJsonCodecProvider::objectMapper);
        return new Jackson2JsonRedisSerializer<>(objectMapper, QueuedTicketRedisPayload.class);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "voicepulse.routing.redis",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    RedisSerializationContext<String, QueuedTicketRedisPayload>
            queuedTicketRedisSerializationContext(
                    RedisSerializer<QueuedTicketRedisPayload> queuedTicketRedisSerializer) {
        return RedisSerializationContext.<String, QueuedTicketRedisPayload>newSerializationContext(
                        StringRedisSerializer.UTF_8)
                .value(queuedTicketRedisSerializer)
                .hashValue(queuedTicketRedisSerializer)
                .build();
    }
}
