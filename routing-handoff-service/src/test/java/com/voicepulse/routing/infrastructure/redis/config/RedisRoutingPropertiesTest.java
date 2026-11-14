package com.voicepulse.routing.infrastructure.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("RedisRoutingProperties & RedisConfiguration Tests")
class RedisRoutingPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(RedisConfiguration.class);

    @Nested
    @DisplayName("Spring Context Binding & Normalization")
    class ContextBindingTests {

        @Test
        @DisplayName("Should bind default Redis routing properties from application.yml")
        void shouldBindDefaultsFromApplicationYml() {
            contextRunner.run(
                    context -> {
                        assertThat(context).hasSingleBean(RedisRoutingProperties.class);

                        RedisRoutingProperties props =
                                context.getBean(RedisRoutingProperties.class);
                        assertThat(props.enabled()).isTrue();
                        assertThat(props.keyPrefix()).isEqualTo("voicepulse:routing");
                        assertThat(props.operationTimeout()).isEqualTo(Duration.ofSeconds(5));
                    });
        }

        @Test
        @DisplayName("Should normalize trailing colons and surrounding whitespace on keyPrefix")
        void shouldNormalizeKeyPrefix() {
            RedisRoutingProperties props =
                    new RedisRoutingProperties(
                            true, "  voicepulse:routing::  ", Duration.ofSeconds(3));

            assertThat(props.keyPrefix()).isEqualTo("voicepulse:routing");
            assertThat(props.operationTimeout()).isEqualTo(Duration.ofSeconds(3));
        }
    }

    @Nested
    @DisplayName("Fail-Fast Compact Constructor Validation")
    class ValidationTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", ":::", "voicepulse routing"})
        @DisplayName("Should reject blank, colon-only, or whitespace-containing keyPrefix")
        void shouldRejectInvalidKeyPrefix(String invalidPrefix) {
            assertThatThrownBy(
                            () ->
                                    new RedisRoutingProperties(
                                            true, invalidPrefix, Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("keyPrefix");
        }

        @Test
        @DisplayName("Should reject null, zero, or negative operationTimeout")
        void shouldRejectInvalidOperationTimeout() {
            assertThatThrownBy(() -> new RedisRoutingProperties(true, "voicepulse:routing", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("operationTimeout");

            assertThatThrownBy(
                            () ->
                                    new RedisRoutingProperties(
                                            true, "voicepulse:routing", Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("operationTimeout");

            assertThatThrownBy(
                            () ->
                                    new RedisRoutingProperties(
                                            true, "voicepulse:routing", Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("operationTimeout");
        }

        @Test
        @DisplayName("Context startup should fail fast when invalid Redis property is provided")
        void shouldFailContextStartupOnInvalidPropertyOverride() {
            contextRunner
                    .withPropertyValues("voicepulse.routing.redis.operation-timeout=0s")
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
