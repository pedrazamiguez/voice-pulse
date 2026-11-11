package com.voicepulse.conversation.infrastructure.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("McpClientProperties & McpClientConfiguration Tests")
class McpClientPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(McpClientConfiguration.class);

    @Nested
    @DisplayName("Spring Context Binding & Defaults")
    class ContextBindingTests {

        @Test
        @DisplayName("Should bind default properties from application.yml and provision beans")
        void shouldBindDefaultsFromApplicationYml() {
            contextRunner.run(
                    context -> {
                        assertThat(context).hasSingleBean(McpClientProperties.class);
                        assertThat(context).hasSingleBean(HttpClient.class);
                        assertThat(context).hasBean("mcpVirtualThreadExecutor");

                        McpClientProperties props = context.getBean(McpClientProperties.class);
                        assertThat(props.crmBaseUrl()).isEqualTo("http://localhost:8091");
                        assertThat(props.ticketBaseUrl()).isEqualTo("http://localhost:8092");
                        assertThat(props.routingBaseUrl()).isEqualTo("http://localhost:8093");
                        assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(5));

                        ExecutorService executor =
                                context.getBean("mcpVirtualThreadExecutor", ExecutorService.class);
                        Boolean isVirtual =
                                executor.submit(() -> Thread.currentThread().isVirtual()).get();
                        assertThat(isVirtual).isTrue();
                    });
        }

        @Test
        @DisplayName("Should strip trailing slash from configured base URLs")
        void shouldStripTrailingSlashFromBaseUrls() {
            McpClientProperties props =
                    new McpClientProperties(
                            "http://localhost:8091/",
                            "https://tickets.internal:8443/",
                            "http://routing.internal:8093/",
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(3));

            assertThat(props.crmBaseUrl()).isEqualTo("http://localhost:8091");
            assertThat(props.ticketBaseUrl()).isEqualTo("https://tickets.internal:8443");
            assertThat(props.routingBaseUrl()).isEqualTo("http://routing.internal:8093");
        }
    }

    @Nested
    @DisplayName("Fail-Fast Compact Constructor Validation")
    class ValidationTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "ftp://localhost:8091", "not-a-url", "http://", "://bad"})
        @DisplayName("Should reject invalid crmBaseUrl")
        void shouldRejectInvalidCrmBaseUrl(String invalidUrl) {
            assertThatThrownBy(
                            () ->
                                    new McpClientProperties(
                                            invalidUrl,
                                            "http://localhost:8092",
                                            "http://localhost:8093",
                                            Duration.ofSeconds(2),
                                            Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("crmBaseUrl");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "ws://localhost:8092", "http://"})
        @DisplayName("Should reject invalid ticketBaseUrl")
        void shouldRejectInvalidTicketBaseUrl(String invalidUrl) {
            assertThatThrownBy(
                            () ->
                                    new McpClientProperties(
                                            "http://localhost:8091",
                                            invalidUrl,
                                            "http://localhost:8093",
                                            Duration.ofSeconds(2),
                                            Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ticketBaseUrl");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "file:///tmp/socket", "https://"})
        @DisplayName("Should reject invalid routingBaseUrl")
        void shouldRejectInvalidRoutingBaseUrl(String invalidUrl) {
            assertThatThrownBy(
                            () ->
                                    new McpClientProperties(
                                            "http://localhost:8091",
                                            "http://localhost:8092",
                                            invalidUrl,
                                            Duration.ofSeconds(2),
                                            Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("routingBaseUrl");
        }

        @Test
        @DisplayName("Should reject null, zero, or negative connectTimeout")
        void shouldRejectInvalidConnectTimeout() {
            assertThatThrownBy(
                            () ->
                                    new McpClientProperties(
                                            "http://localhost:8091",
                                            "http://localhost:8092",
                                            "http://localhost:8093",
                                            null,
                                            Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("connectTimeout");

            assertThatThrownBy(
                            () ->
                                    new McpClientProperties(
                                            "http://localhost:8091",
                                            "http://localhost:8092",
                                            "http://localhost:8093",
                                            Duration.ZERO,
                                            Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("connectTimeout");

            assertThatThrownBy(
                            () ->
                                    new McpClientProperties(
                                            "http://localhost:8091",
                                            "http://localhost:8092",
                                            "http://localhost:8093",
                                            Duration.ofMillis(-100),
                                            Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("connectTimeout");
        }

        @Test
        @DisplayName("Should reject null, zero, or negative requestTimeout")
        void shouldRejectInvalidRequestTimeout() {
            assertThatThrownBy(
                            () ->
                                    new McpClientProperties(
                                            "http://localhost:8091",
                                            "http://localhost:8092",
                                            "http://localhost:8093",
                                            Duration.ofSeconds(2),
                                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requestTimeout");

            assertThatThrownBy(
                            () ->
                                    new McpClientProperties(
                                            "http://localhost:8091",
                                            "http://localhost:8092",
                                            "http://localhost:8093",
                                            Duration.ofSeconds(2),
                                            Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requestTimeout");

            assertThatThrownBy(
                            () ->
                                    new McpClientProperties(
                                            "http://localhost:8091",
                                            "http://localhost:8092",
                                            "http://localhost:8093",
                                            Duration.ofSeconds(2),
                                            Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requestTimeout");
        }

        @Test
        @DisplayName("Context startup should fail fast when invalid property override is provided")
        void shouldFailContextStartupOnInvalidPropertyOverride() {
            contextRunner
                    .withPropertyValues("voicepulse.conversation.mcp.request-timeout=0s")
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
