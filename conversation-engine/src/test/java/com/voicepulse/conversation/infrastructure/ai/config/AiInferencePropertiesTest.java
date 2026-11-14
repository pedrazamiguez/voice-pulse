package com.voicepulse.conversation.infrastructure.ai.config;

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

@DisplayName("AiInferenceProperties & AiInferenceConfiguration Tests")
class AiInferencePropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(AiInferenceConfiguration.class);

    @Nested
    @DisplayName("Spring Context Binding & Virtual Thread Provisions")
    class ContextBindingTests {

        @Test
        @DisplayName("Should bind voicepulse.conversation.ai defaults from application.yml")
        void shouldBindDefaultsFromApplicationYml() {
            contextRunner.run(
                    context -> {
                        assertThat(context).hasSingleBean(AiInferenceProperties.class);
                        assertThat(context).hasBean("aiHttpClient");
                        assertThat(context).hasBean("aiVirtualThreadExecutor");

                        AiInferenceProperties props = context.getBean(AiInferenceProperties.class);
                        assertThat(props.mode()).isEqualTo(AiProcessingMode.MOCK);
                        assertThat(props.endpointUrl())
                                .isEqualTo("http://localhost:11434/v1/chat/completions");
                        assertThat(props.model()).isEqualTo("llama3.1");
                        assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(5));
                        assertThat(props.apiKey()).isEmpty();

                        ExecutorService executor =
                                context.getBean("aiVirtualThreadExecutor", ExecutorService.class);
                        Boolean isVirtual =
                                executor.submit(() -> Thread.currentThread().isVirtual()).get();
                        assertThat(isVirtual).isTrue();
                        assertThat(context.getBean("aiHttpClient", HttpClient.class)).isNotNull();
                    });
        }

        @Test
        @DisplayName("Should bind live mode override cleanly")
        void shouldBindLiveModeOverride() {
            contextRunner
                    .withPropertyValues(
                            "voicepulse.conversation.ai.mode=live",
                            "voicepulse.conversation.ai.model=gpt-4o-mini",
                            "voicepulse.conversation.ai.api-key=secret-key")
                    .run(
                            context -> {
                                AiInferenceProperties props =
                                        context.getBean(AiInferenceProperties.class);
                                assertThat(props.mode()).isEqualTo(AiProcessingMode.LIVE);
                                assertThat(props.model()).isEqualTo("gpt-4o-mini");
                                assertThat(props.apiKey()).isEqualTo("secret-key");
                            });
        }
    }

    @Nested
    @DisplayName("Fail-Fast Compact Constructor Validation")
    class ValidationTests {

        @Test
        @DisplayName("Should reject null mode")
        void shouldRejectNullMode() {
            assertThatThrownBy(
                            () ->
                                    new AiInferenceProperties(
                                            null,
                                            "http://localhost:11434/v1/chat/completions",
                                            "llama3.1",
                                            Duration.ofSeconds(5),
                                            ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mode");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "ftp://localhost:11434", "not-a-url", "http://", "://bad"})
        @DisplayName("Should reject invalid endpointUrl")
        void shouldRejectInvalidEndpointUrl(String invalidUrl) {
            assertThatThrownBy(
                            () ->
                                    new AiInferenceProperties(
                                            AiProcessingMode.LIVE,
                                            invalidUrl,
                                            "llama3.1",
                                            Duration.ofSeconds(5),
                                            ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endpointUrl");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Should reject blank model")
        void shouldRejectBlankModel(String invalidModel) {
            assertThatThrownBy(
                            () ->
                                    new AiInferenceProperties(
                                            AiProcessingMode.LIVE,
                                            "http://localhost:11434/v1/chat/completions",
                                            invalidModel,
                                            Duration.ofSeconds(5),
                                            ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("model");
        }

        @Test
        @DisplayName("Should reject null, zero, or negative timeout")
        void shouldRejectInvalidTimeout() {
            assertThatThrownBy(
                            () ->
                                    new AiInferenceProperties(
                                            AiProcessingMode.LIVE,
                                            "http://localhost:11434/v1/chat/completions",
                                            "llama3.1",
                                            null,
                                            ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout");

            assertThatThrownBy(
                            () ->
                                    new AiInferenceProperties(
                                            AiProcessingMode.LIVE,
                                            "http://localhost:11434/v1/chat/completions",
                                            "llama3.1",
                                            Duration.ZERO,
                                            ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout");

            assertThatThrownBy(
                            () ->
                                    new AiInferenceProperties(
                                            AiProcessingMode.LIVE,
                                            "http://localhost:11434/v1/chat/completions",
                                            "llama3.1",
                                            Duration.ofSeconds(-2),
                                            ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeout");
        }

        @Test
        @DisplayName("Context startup should fail fast when invalid property override is provided")
        void shouldFailContextStartupOnInvalidPropertyOverride() {
            contextRunner
                    .withPropertyValues("voicepulse.conversation.ai.timeout=0s")
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
