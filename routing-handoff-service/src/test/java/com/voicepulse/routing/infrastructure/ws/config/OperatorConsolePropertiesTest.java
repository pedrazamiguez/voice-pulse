package com.voicepulse.routing.infrastructure.ws.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("OperatorConsoleProperties Tests")
class OperatorConsolePropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(PropertiesBindingConfig.class);

    @Configuration
    @EnableConfigurationProperties(OperatorConsoleProperties.class)
    static class PropertiesBindingConfig {}

    @Nested
    @DisplayName("Normalization & Bridge URL Composition")
    class NormalizationTests {

        @Test
        @DisplayName("Binds defaults from application.yml via Spring Context")
        void bindsDefaultsFromApplicationYml() {
            contextRunner.run(
                    context -> {
                        assertThat(context).hasSingleBean(OperatorConsoleProperties.class);
                        OperatorConsoleProperties props =
                                context.getBean(OperatorConsoleProperties.class);
                        assertThat(props.gatewayWsBaseUrl()).isEqualTo("ws://localhost:8080");
                        assertThat(props.heartbeatInterval()).isEqualTo(Duration.ofSeconds(10));
                        assertThat(props.outboundBufferCapacity()).isEqualTo(256);
                    });
        }

        @Test
        @DisplayName("Normalizes trailing slashes and resolves relative bridge endpoints")
        void normalizesAndResolvesBridgeUrl() {
            OperatorConsoleProperties props =
                    new OperatorConsoleProperties(
                            "  wss://gw.voicepulse.io/edge/  ", Duration.ofSeconds(15), 512);

            assertThat(props.gatewayWsBaseUrl()).isEqualTo("wss://gw.voicepulse.io/edge");
            assertThat(props.resolveBridgeUrl("/ws/operators/op-1/calls/123/audio"))
                    .isEqualTo("wss://gw.voicepulse.io/edge/ws/operators/op-1/calls/123/audio");
            assertThat(props.resolveBridgeUrl("ws/operators/op-1/calls/123/audio"))
                    .isEqualTo("wss://gw.voicepulse.io/edge/ws/operators/op-1/calls/123/audio");
            assertThat(props.resolveBridgeUrl("ws://custom:9000/audio"))
                    .isEqualTo("ws://custom:9000/audio");
            assertThat(props.resolveBridgeUrl("   ")).isNull();
        }
    }

    @Nested
    @DisplayName("Fail-Fast Compact Constructor Validation")
    class ValidationTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "http://localhost:8080", "https://gw.io", "ws:///nohost"})
        @DisplayName("Rejects invalid gatewayWsBaseUrl values")
        void rejectsInvalidGatewayWsBaseUrl(String invalidUrl) {
            assertThatThrownBy(
                            () ->
                                    new OperatorConsoleProperties(
                                            invalidUrl, Duration.ofSeconds(10), 256))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects null, zero, or negative heartbeatInterval")
        void rejectsInvalidHeartbeatInterval() {
            Duration negative = Duration.ofSeconds(-1);
            assertThatThrownBy(() -> new OperatorConsoleProperties("ws://localhost:8080", null, 64))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("heartbeatInterval");
            assertThatThrownBy(
                            () ->
                                    new OperatorConsoleProperties(
                                            "ws://localhost:8080", Duration.ZERO, 64))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("heartbeatInterval");
            assertThatThrownBy(
                            () ->
                                    new OperatorConsoleProperties(
                                            "ws://localhost:8080", negative, 64))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("heartbeatInterval");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -100})
        @DisplayName("Rejects non-positive outboundBufferCapacity")
        void rejectsInvalidOutboundBufferCapacity(int invalidCapacity) {
            Duration heartbeat = Duration.ofSeconds(10);
            assertThatThrownBy(
                            () ->
                                    new OperatorConsoleProperties(
                                            "ws://localhost:8080", heartbeat, invalidCapacity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outboundBufferCapacity");
        }
    }
}
