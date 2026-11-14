package com.voicepulse.routing.infrastructure.config;

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

@DisplayName("RoutingProperties & RoutingConfiguration Tests")
class RoutingPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(RoutingConfiguration.class);

    @Nested
    @DisplayName("Spring Context Binding & Normalization")
    class ContextBindingTests {

        @Test
        @DisplayName("Should bind default properties from application.yml")
        void shouldBindDefaultsFromApplicationYml() {
            contextRunner.run(
                    context -> {
                        assertThat(context).hasSingleBean(RoutingProperties.class);

                        RoutingProperties props = context.getBean(RoutingProperties.class);
                        assertThat(props.ticketIntentTopic()).isEqualTo("telephony.tickets.intent");
                        assertThat(props.callControlTopic()).isEqualTo("telephony.calls.control");
                        assertThat(props.consumerGroup()).isEqualTo("routing-handoff-workers");
                        assertThat(props.operatorHeartbeatTtl()).isEqualTo(Duration.ofSeconds(30));
                        assertThat(props.operatorBridgeBasePath()).isEqualTo("/ws/operators");
                        assertThat(props.maxQueueCapacity()).isEqualTo(1000);
                    });
        }

        @Test
        @DisplayName("Should trim topics and strip trailing slash from operatorBridgeBasePath")
        void shouldNormalizeConfiguredValues() {
            RoutingProperties props =
                    new RoutingProperties(
                            "  telephony.tickets.intent  ",
                            "  telephony.calls.control ",
                            " routing-workers ",
                            Duration.ofSeconds(45),
                            "/ws/operators/",
                            500);

            assertThat(props.ticketIntentTopic()).isEqualTo("telephony.tickets.intent");
            assertThat(props.callControlTopic()).isEqualTo("telephony.calls.control");
            assertThat(props.consumerGroup()).isEqualTo("routing-workers");
            assertThat(props.operatorBridgeBasePath()).isEqualTo("/ws/operators");
        }
    }

    @Nested
    @DisplayName("Fail-Fast Compact Constructor Validation")
    class ValidationTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Should reject blank or null ticketIntentTopic")
        void shouldRejectInvalidTicketIntentTopic(String invalid) {
            assertThatThrownBy(
                            () ->
                                    new RoutingProperties(
                                            invalid,
                                            "telephony.calls.control",
                                            "routing-workers",
                                            Duration.ofSeconds(30),
                                            "/ws/operators",
                                            1000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ticketIntentTopic");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Should reject blank or null callControlTopic")
        void shouldRejectInvalidCallControlTopic(String invalid) {
            assertThatThrownBy(
                            () ->
                                    new RoutingProperties(
                                            "telephony.tickets.intent",
                                            invalid,
                                            "routing-workers",
                                            Duration.ofSeconds(30),
                                            "/ws/operators",
                                            1000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("callControlTopic");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Should reject blank or null consumerGroup")
        void shouldRejectInvalidConsumerGroup(String invalid) {
            assertThatThrownBy(
                            () ->
                                    new RoutingProperties(
                                            "telephony.tickets.intent",
                                            "telephony.calls.control",
                                            invalid,
                                            Duration.ofSeconds(30),
                                            "/ws/operators",
                                            1000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("consumerGroup");
        }

        @Test
        @DisplayName("Should reject null, zero, or negative operatorHeartbeatTtl")
        void shouldRejectInvalidHeartbeatTtl() {
            assertThatThrownBy(
                            () ->
                                    new RoutingProperties(
                                            "telephony.tickets.intent",
                                            "telephony.calls.control",
                                            "routing-workers",
                                            null,
                                            "/ws/operators",
                                            1000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("operatorHeartbeatTtl");

            assertThatThrownBy(
                            () ->
                                    new RoutingProperties(
                                            "telephony.tickets.intent",
                                            "telephony.calls.control",
                                            "routing-workers",
                                            Duration.ZERO,
                                            "/ws/operators",
                                            1000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("operatorHeartbeatTtl");

            assertThatThrownBy(
                            () ->
                                    new RoutingProperties(
                                            "telephony.tickets.intent",
                                            "telephony.calls.control",
                                            "routing-workers",
                                            Duration.ofSeconds(-5),
                                            "/ws/operators",
                                            1000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("operatorHeartbeatTtl");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "ws/operators"})
        @DisplayName("Should reject invalid operatorBridgeBasePath")
        void shouldRejectInvalidBridgeBasePath(String invalid) {
            assertThatThrownBy(
                            () ->
                                    new RoutingProperties(
                                            "telephony.tickets.intent",
                                            "telephony.calls.control",
                                            "routing-workers",
                                            Duration.ofSeconds(30),
                                            invalid,
                                            1000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("operatorBridgeBasePath");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -50})
        @DisplayName("Should reject non-positive maxQueueCapacity")
        void shouldRejectNonPositiveMaxQueueCapacity(int invalidCapacity) {
            assertThatThrownBy(
                            () ->
                                    new RoutingProperties(
                                            "telephony.tickets.intent",
                                            "telephony.calls.control",
                                            "routing-workers",
                                            Duration.ofSeconds(30),
                                            "/ws/operators",
                                            invalidCapacity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxQueueCapacity");
        }

        @Test
        @DisplayName("Context startup should fail fast when invalid property override is provided")
        void shouldFailContextStartupOnInvalidPropertyOverride() {
            contextRunner
                    .withPropertyValues("voicepulse.routing.max-queue-capacity=0")
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
