package com.voicepulse.conversation.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.conversation.application.dialog.ConversationResponseGenerator;
import com.voicepulse.conversation.application.dialog.MockLlmConversationProcessor;
import com.voicepulse.conversation.application.registry.ConversationSessionRegistry;
import com.voicepulse.conversation.application.service.ConversationOrchestratorService;
import com.voicepulse.conversation.domain.ports.inbound.ProcessAudioChunkUseCase;
import com.voicepulse.conversation.domain.ports.inbound.ProgressConversationUseCase;
import com.voicepulse.conversation.domain.service.SkillGroupRoutingPolicy;
import com.voicepulse.conversation.infrastructure.fallback.NoOpCallControlDispatcherPort;
import com.voicepulse.conversation.infrastructure.fallback.NoOpConversationStatePublisherPort;
import com.voicepulse.conversation.infrastructure.fallback.NoOpProcessAudioChunkUseCase;
import com.voicepulse.conversation.infrastructure.fallback.NoOpProgressConversationUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("ConversationApplicationConfiguration & Spring Context Tests")
class ConversationApplicationConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            ConversationApplicationConfiguration.class,
                            ConversationSessionRegistry.class,
                            ConversationResponseGenerator.class,
                            MockLlmConversationProcessor.class,
                            ConversationOrchestratorService.class,
                            NoOpProcessAudioChunkUseCase.class,
                            NoOpProgressConversationUseCase.class,
                            NoOpCallControlDispatcherPort.class,
                            NoOpConversationStatePublisherPort.class);

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
    @DisplayName("Application beans and session registry are correctly registered in context")
    void applicationBeans_arePresentInContext() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(ConversationSessionRegistry.class);
                    assertThat(context).hasSingleBean(ConversationResponseGenerator.class);
                    assertThat(context).hasSingleBean(MockLlmConversationProcessor.class);
                    assertThat(context).hasSingleBean(ConversationOrchestratorService.class);
                    assertThat(context).hasSingleBean(SkillGroupRoutingPolicy.class);
                });
    }
}
