package com.voicepulse.gateway.infrastructure.config;

import com.voicepulse.gateway.domain.ports.outbound.AudioFramePublisherPort;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.gateway.domain.service.CallSessionService;
import com.voicepulse.gateway.infrastructure.persistence.memory.InMemorySessionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration providing domain port and service beans with dependency inversion. */
@Configuration
public class GatewayDomainConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SessionRegistryPort sessionRegistryPort() {
        return new InMemorySessionRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public AudioFramePublisherPort audioFramePublisherPort() {
        return (callId, sequenceNumber, audioPayload) -> {};
    }

    @Bean
    @ConditionalOnMissingBean
    public CallSessionService callSessionService(
            SessionRegistryPort sessionRegistryPort,
            AudioFramePublisherPort audioFramePublisherPort) {
        return new CallSessionService(sessionRegistryPort, audioFramePublisherPort);
    }
}
