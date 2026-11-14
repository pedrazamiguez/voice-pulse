package com.voicepulse.routing.infrastructure.kafka.config;

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

@DisplayName("RoutingKafkaProperties & RoutingKafkaConfiguration Tests")
class RoutingKafkaPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(RoutingKafkaConfiguration.class);

    @Nested
    @DisplayName("Spring Context Binding & Normalization")
    class ContextBindingTests {

        @Test
        @DisplayName("Should bind Kafka properties from application.yml")
        void shouldBindDefaultsFromApplicationYml() {
            contextRunner.run(
                    context -> {
                        assertThat(context).hasSingleBean(RoutingKafkaProperties.class);

                        RoutingKafkaProperties props =
                                context.getBean(RoutingKafkaProperties.class);
                        assertThat(props.enabled()).isTrue();
                        assertThat(props.concurrency()).isEqualTo(3);
                        assertThat(props.autoOffsetReset()).isEqualTo("earliest");
                        assertThat(props.sendTimeout()).isEqualTo(Duration.ofSeconds(5));
                        assertThat(props.dlqTopic()).isEqualTo("telephony.calls.dlq");
                    });
        }

        @Test
        @DisplayName("Should normalize autoOffsetReset and trim dlqTopic")
        void shouldNormalizeValues() {
            RoutingKafkaProperties props =
                    new RoutingKafkaProperties(
                            true,
                            4,
                            "  EARLIEST  ",
                            Duration.ofSeconds(3),
                            "  telephony.calls.dlq  ");

            assertThat(props.autoOffsetReset()).isEqualTo("earliest");
            assertThat(props.dlqTopic()).isEqualTo("telephony.calls.dlq");
        }
    }

    @Nested
    @DisplayName("Fail-Fast Compact Constructor Validation")
    class ValidationTests {

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -5})
        @DisplayName("Should reject non-positive concurrency")
        void shouldRejectNonPositiveConcurrency(int invalidConcurrency) {
            assertThatThrownBy(
                            () ->
                                    new RoutingKafkaProperties(
                                            true,
                                            invalidConcurrency,
                                            "earliest",
                                            Duration.ofSeconds(5),
                                            "telephony.calls.dlq"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("concurrency");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "invalid-policy", "beginning"})
        @DisplayName("Should reject invalid autoOffsetReset policy")
        void shouldRejectInvalidAutoOffsetReset(String invalidPolicy) {
            assertThatThrownBy(
                            () ->
                                    new RoutingKafkaProperties(
                                            true,
                                            3,
                                            invalidPolicy,
                                            Duration.ofSeconds(5),
                                            "telephony.calls.dlq"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("autoOffsetReset");
        }

        @Test
        @DisplayName("Should reject null, zero, or negative sendTimeout")
        void shouldRejectInvalidSendTimeout() {
            assertThatThrownBy(
                            () ->
                                    new RoutingKafkaProperties(
                                            true, 3, "earliest", null, "telephony.calls.dlq"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendTimeout");

            assertThatThrownBy(
                            () ->
                                    new RoutingKafkaProperties(
                                            true,
                                            3,
                                            "earliest",
                                            Duration.ZERO,
                                            "telephony.calls.dlq"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendTimeout");

            assertThatThrownBy(
                            () ->
                                    new RoutingKafkaProperties(
                                            true,
                                            3,
                                            "earliest",
                                            Duration.ofMillis(-50),
                                            "telephony.calls.dlq"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendTimeout");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Should reject null or blank dlqTopic")
        void shouldRejectInvalidDlqTopic(String invalidTopic) {
            assertThatThrownBy(
                            () ->
                                    new RoutingKafkaProperties(
                                            true,
                                            3,
                                            "earliest",
                                            Duration.ofSeconds(5),
                                            invalidTopic))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dlqTopic");
        }
    }
}
