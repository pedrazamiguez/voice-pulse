package com.voicepulse.routing.infrastructure.redis.config;

import com.voicepulse.routing.infrastructure.redis.model.QueuedTicketRedisPayload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Provisions the typed {@link ReactiveRedisTemplate} for {@link QueuedTicketRedisPayload} when
 * Redis routing is enabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "voicepulse.routing.redis",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RedisTemplateConfiguration {

    @Bean
    ReactiveRedisTemplate<String, QueuedTicketRedisPayload> queuedTicketRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory,
            RedisSerializationContext<String, QueuedTicketRedisPayload>
                    queuedTicketRedisSerializationContext) {
        return new ReactiveRedisTemplate<>(
                connectionFactory, queuedTicketRedisSerializationContext);
    }
}
