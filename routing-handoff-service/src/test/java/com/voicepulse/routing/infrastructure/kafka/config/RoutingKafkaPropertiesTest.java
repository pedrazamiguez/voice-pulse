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

    private static final RoutingKafkaTopicProperties VALID_TOPICS =
            new RoutingKafkaTopicProperties(
                    new RoutingTopicSpec(6, 1),
                    new RoutingTopicSpec(12, 1),
                    new RoutingTopicSpec(3, 1));

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(RoutingKafkaConfiguration.class);

    @Nested
    @DisplayName("Spring Context Binding & Normalization")
    class ContextBindingTests {

        @Test
        @DisplayName("Should bind Kafka and topic topology properties from application.yml")
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
                        assertThat(props.retryBackoff()).isEqualTo(Duration.ofMillis(500));
                        assertThat(props.maxRetries()).isEqualTo(2);
                        assertThat(props.dlqTopic()).isEqualTo("telephony.calls.dlq");
                        assertThat(props.topics().ticketIntent())
                                .isEqualTo(new RoutingTopicSpec(6, 1));
                        assertThat(props.topics().callControl())
                                .isEqualTo(new RoutingTopicSpec(12, 1));
                        assertThat(props.topics().dlq()).isEqualTo(new RoutingTopicSpec(3, 1));
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
                            Duration.ZERO,
                            0,
                            "  telephony.calls.dlq  ",
                            VALID_TOPICS);

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
                                            Duration.ofMillis(500),
                                            2,
                                            "telephony.calls.dlq",
                                            VALID_TOPICS))
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
                                            Duration.ofMillis(500),
                                            2,
                                            "telephony.calls.dlq",
                                            VALID_TOPICS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("autoOffsetReset");
        }

        @Test
        @DisplayName("Should reject invalid sendTimeout, retryBackoff, and maxRetries")
        void shouldRejectInvalidTimingsAndRetries() {
            assertThatThrownBy(
                            () ->
                                    new RoutingKafkaProperties(
                                            true,
                                            3,
                                            "earliest",
                                            null,
                                            Duration.ofMillis(500),
                                            2,
                                            "telephony.calls.dlq",
                                            VALID_TOPICS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendTimeout");

            assertThatThrownBy(
                            () ->
                                    new RoutingKafkaProperties(
                                            true,
                                            3,
                                            "earliest",
                                            Duration.ZERO,
                                            Duration.ofMillis(500),
                                            2,
                                            "telephony.calls.dlq",
                                            VALID_TOPICS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendTimeout");

            assertThatThrownBy(
                            () ->
                                    new RoutingKafkaProperties(
                                            true,
                                            3,
                                            "earliest",
                                            Duration.ofSeconds(5),
                                            Duration.ofMillis(-1),
                                            2,
                                            "telephony.calls.dlq",
                                            VALID_TOPICS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retryBackoff");

            assertThatThrownBy(
                            () ->
                                    new RoutingKafkaProperties(
                                            true,
                                            3,
                                            "earliest",
                                            Duration.ofSeconds(5),
                                            Duration.ofMillis(500),
                                            -1,
                                            "telephony.calls.dlq",
                                            VALID_TOPICS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxRetries");
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
                                            Duration.ofMillis(500),
                                            2,
                                            invalidTopic,
                                            VALID_TOPICS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dlqTopic");
        }

        @Test
        @DisplayName("Should reject null topic topology container or invalid partitions/replicas")
        void shouldRejectInvalidTopicTopology() {
            assertThatThrownBy(
                            () ->
                                    new RoutingKafkaProperties(
                                            true,
                                            3,
                                            "earliest",
                                            Duration.ofSeconds(5),
                                            Duration.ofMillis(500),
                                            2,
                                            "telephony.calls.dlq",
                                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("topics");

            assertThatThrownBy(() -> new RoutingTopicSpec(0, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("partitions");
            assertThatThrownBy(() -> new RoutingTopicSpec(6, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("replicas");
            assertThatThrownBy(
                            () ->
                                    new RoutingKafkaTopicProperties(
                                            null,
                                            new RoutingTopicSpec(12, 1),
                                            new RoutingTopicSpec(3, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ticketIntent");
        }
    }
}
