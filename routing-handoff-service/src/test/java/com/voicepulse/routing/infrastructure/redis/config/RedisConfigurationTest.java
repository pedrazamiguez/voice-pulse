package com.voicepulse.routing.infrastructure.redis.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.routing.infrastructure.redis.model.QueuedTicketRedisPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

@DisplayName("Redis Configuration Bean Wiring Tests")
class RedisConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(
                            RedisConfiguration.class, RedisTemplateConfiguration.class);

    @Test
    @DisplayName(
            "Should register serializer, context, and typed ReactiveRedisTemplate when Redis is"
                    + " enabled")
    void shouldRegisterTypedRedisBeansWhenEnabled() {
        contextRunner
                .withBean(
                        ReactiveRedisConnectionFactory.class,
                        () -> Mockito.mock(ReactiveRedisConnectionFactory.class))
                .withPropertyValues("voicepulse.routing.redis.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(RedisSerializer.class);
                            assertThat(context).hasSingleBean(RedisSerializationContext.class);
                            assertThat(context).hasSingleBean(ReactiveRedisTemplate.class);
                            @SuppressWarnings("unchecked")
                            ReactiveRedisTemplate<String, QueuedTicketRedisPayload> template =
                                    context.getBean(ReactiveRedisTemplate.class);
                            assertThat(template.getSerializationContext()).isNotNull();
                        });
    }

    @Test
    @DisplayName(
            "Should skip serializer, context, and ReactiveRedisTemplate beans when Redis is"
                    + " disabled")
    void shouldSkipTypedRedisBeansWhenDisabled() {
        contextRunner
                .withPropertyValues("voicepulse.routing.redis.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(RedisRoutingProperties.class);
                            assertThat(context).doesNotHaveBean(RedisSerializer.class);
                            assertThat(context).doesNotHaveBean(RedisSerializationContext.class);
                            assertThat(context).doesNotHaveBean(ReactiveRedisTemplate.class);
                        });
    }
}
