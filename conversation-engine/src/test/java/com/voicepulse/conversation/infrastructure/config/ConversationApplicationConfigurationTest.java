package com.voicepulse.conversation.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.conversation.application.dialog.ConversationResponseGenerator;
import com.voicepulse.conversation.application.dialog.DisruptionTriageCoordinator;
import com.voicepulse.conversation.application.dialog.MockLlmConversationProcessor;
import com.voicepulse.conversation.application.registry.ConversationSessionRegistry;
import com.voicepulse.conversation.application.service.ConversationOrchestratorService;
import com.voicepulse.conversation.domain.ports.inbound.ProcessAudioChunkUseCase;
import com.voicepulse.conversation.domain.ports.inbound.ProgressConversationUseCase;
import com.voicepulse.conversation.domain.ports.outbound.ConversationDialogProcessorPort;
import com.voicepulse.conversation.domain.service.SkillGroupRoutingPolicy;
import com.voicepulse.conversation.infrastructure.ai.LiveLlmConversationProcessor;
import com.voicepulse.conversation.infrastructure.ai.LlmPromptBuilder;
import com.voicepulse.conversation.infrastructure.ai.LlmToolCallDispatcher;
import com.voicepulse.conversation.infrastructure.ai.OpenAiCompatibleChatClient;
import com.voicepulse.conversation.infrastructure.ai.config.AiInferenceConfiguration;
import com.voicepulse.conversation.infrastructure.fallback.NoOpCallControlDispatcherPort;
import com.voicepulse.conversation.infrastructure.fallback.NoOpConversationDialogProcessorPort;
import com.voicepulse.conversation.infrastructure.fallback.NoOpConversationStatePublisherPort;
import com.voicepulse.conversation.infrastructure.fallback.NoOpCustomerLookupPort;
import com.voicepulse.conversation.infrastructure.fallback.NoOpDisruptionTicketPort;
import com.voicepulse.conversation.infrastructure.fallback.NoOpOperatorRoutingPort;
import com.voicepulse.conversation.infrastructure.fallback.NoOpProcessAudioChunkUseCase;
import com.voicepulse.conversation.infrastructure.fallback.NoOpProgressConversationUseCase;
import com.voicepulse.conversation.infrastructure.fallback.NoOpTicketIntentPublisherPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("ConversationApplicationConfiguration & Spring Context Tests")
class ConversationApplicationConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(
                            ConversationApplicationConfiguration.class,
                            AiInferenceConfiguration.class,
                            ConversationSessionRegistry.class,
                            ConversationResponseGenerator.class,
                            DisruptionTriageCoordinator.class,
                            MockLlmConversationProcessor.class,
                            LlmPromptBuilder.class,
                            OpenAiCompatibleChatClient.class,
                            LlmToolCallDispatcher.class,
                            LiveLlmConversationProcessor.class,
                            ConversationOrchestratorService.class,
                            NoOpProcessAudioChunkUseCase.class,
                            NoOpProgressConversationUseCase.class,
                            NoOpCallControlDispatcherPort.class,
                            NoOpConversationStatePublisherPort.class,
                            NoOpCustomerLookupPort.class,
                            NoOpDisruptionTicketPort.class,
                            NoOpOperatorRoutingPort.class,
                            NoOpTicketIntentPublisherPort.class,
                            NoOpConversationDialogProcessorPort.class);

    @Test
    @DisplayName(
            "ConversationOrchestratorService cleanly supersedes NoOpProcessAudioChunkUseCase"
                    + " fallback bean")
    void processAudioChunkUseCase_supersedesFallback() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(ConversationOrchestratorService.class);
                    assertThat(context).hasBean("noOpProcessAudioChunkUseCase");

                    ProcessAudioChunkUseCase audioUseCase =
                            context.getBean(ProcessAudioChunkUseCase.class);
                    assertThat(audioUseCase)
                            .isInstanceOf(ConversationOrchestratorService.class)
                            .isNotInstanceOf(NoOpProcessAudioChunkUseCase.class);
                });
    }

    @Test
    @DisplayName(
            "ConversationOrchestratorService cleanly supersedes NoOpProgressConversationUseCase"
                    + " fallback bean")
    void progressConversationUseCase_supersedesFallback() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(ConversationOrchestratorService.class);
                    assertThat(context).hasBean("noOpProgressConversationUseCase");

                    ProgressConversationUseCase progressUseCase =
                            context.getBean(ProgressConversationUseCase.class);
                    assertThat(progressUseCase)
                            .isInstanceOf(ConversationOrchestratorService.class)
                            .isNotInstanceOf(NoOpProgressConversationUseCase.class);
                });
    }

    @Test
    @DisplayName("Default mock AI mode activates MockLlmConversationProcessor")
    void applicationBeans_defaultMockModeSelectsMockProcessor() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(ConversationSessionRegistry.class);
                    assertThat(context).hasSingleBean(ConversationResponseGenerator.class);
                    assertThat(context).hasSingleBean(DisruptionTriageCoordinator.class);
                    assertThat(context).hasSingleBean(MockLlmConversationProcessor.class);
                    assertThat(context).doesNotHaveBean(LiveLlmConversationProcessor.class);
                    assertThat(context).hasSingleBean(ConversationOrchestratorService.class);
                    assertThat(context).hasSingleBean(SkillGroupRoutingPolicy.class);

                    ConversationDialogProcessorPort port =
                            context.getBean(ConversationDialogProcessorPort.class);
                    assertThat(port)
                            .isInstanceOf(MockLlmConversationProcessor.class)
                            .isNotInstanceOf(NoOpConversationDialogProcessorPort.class);
                });
    }

    @Test
    @DisplayName("Live AI mode activates LiveLlmConversationProcessor and disables mock processor")
    void applicationBeans_liveModeSelectsLiveProcessor() {
        contextRunner
                .withPropertyValues("voicepulse.conversation.ai.mode=live")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(LiveLlmConversationProcessor.class);
                            assertThat(context).doesNotHaveBean(MockLlmConversationProcessor.class);

                            ConversationDialogProcessorPort port =
                                    context.getBean(ConversationDialogProcessorPort.class);
                            assertThat(port)
                                    .isInstanceOf(LiveLlmConversationProcessor.class)
                                    .isNotInstanceOf(NoOpConversationDialogProcessorPort.class);
                        });
    }
}
