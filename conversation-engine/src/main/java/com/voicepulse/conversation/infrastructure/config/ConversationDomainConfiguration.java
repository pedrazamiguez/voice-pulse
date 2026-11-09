package com.voicepulse.conversation.infrastructure.config;

import com.voicepulse.conversation.domain.ports.inbound.ProcessAudioChunkUseCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration providing domain port and use case beans with dependency inversion. */
@Slf4j
@Configuration
public class ConversationDomainConfiguration {

    @Bean
    @ConditionalOnMissingBean(ProcessAudioChunkUseCase.class)
    public ProcessAudioChunkUseCase defaultProcessAudioChunkUseCase() {
        return chunk ->
                log.debug(
                        "Default no-op ProcessAudioChunkUseCase processing chunk: sessionId={},"
                                + " seq={}, bytes={}",
                        chunk.sessionId(),
                        chunk.sequenceNumber(),
                        chunk.pcmData().length);
    }
}
