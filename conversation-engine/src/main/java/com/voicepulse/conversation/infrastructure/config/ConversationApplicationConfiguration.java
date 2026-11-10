package com.voicepulse.conversation.infrastructure.config;

import com.voicepulse.conversation.domain.vad.BargeInEvaluator;
import com.voicepulse.conversation.domain.vad.VoiceActivityDetector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing beans for pure domain VAD evaluators and conversational
 * intelligence services.
 */
@Configuration
public class ConversationApplicationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public VoiceActivityDetector voiceActivityDetector() {
        return new VoiceActivityDetector();
    }

    @Bean
    @ConditionalOnMissingBean
    public BargeInEvaluator bargeInEvaluator(VoiceActivityDetector voiceActivityDetector) {
        return new BargeInEvaluator(voiceActivityDetector);
    }
}
