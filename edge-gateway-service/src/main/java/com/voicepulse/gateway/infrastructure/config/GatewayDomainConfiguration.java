package com.voicepulse.gateway.infrastructure.config;

import com.voicepulse.gateway.domain.ports.outbound.AudioFramePublisherPort;
import com.voicepulse.gateway.domain.ports.outbound.AudioPlaybackControlPort;
import com.voicepulse.gateway.domain.ports.outbound.SessionRegistryPort;
import com.voicepulse.gateway.domain.service.CallSessionService;
import com.voicepulse.gateway.infrastructure.echo.EchoLatencyTracker;
import com.voicepulse.gateway.infrastructure.echo.LoopbackAudioEchoAdapter;
import com.voicepulse.gateway.infrastructure.registry.ConcurrentSessionRegistry;
import com.voicepulse.gateway.infrastructure.ws.WebSocketPlaybackControlAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration providing domain port and service beans with dependency inversion. */
@Configuration
public class GatewayDomainConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConcurrentSessionRegistry concurrentSessionRegistry() {
        return new ConcurrentSessionRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionRegistryPort sessionRegistryPort(
            ConcurrentSessionRegistry concurrentSessionRegistry) {
        return concurrentSessionRegistry;
    }

    @Bean
    @ConditionalOnMissingBean
    public EchoLatencyTracker echoLatencyTracker() {
        return new EchoLatencyTracker();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "voicepulse.gateway",
            name = "audio-publisher",
            havingValue = "loopback")
    @ConditionalOnMissingBean(AudioFramePublisherPort.class)
    public AudioFramePublisherPort loopbackAudioFramePublisherPort(
            ConcurrentSessionRegistry concurrentSessionRegistry,
            EchoLatencyTracker echoLatencyTracker) {
        return new LoopbackAudioEchoAdapter(concurrentSessionRegistry, echoLatencyTracker);
    }

    @Bean
    @ConditionalOnMissingBean(AudioPlaybackControlPort.class)
    public AudioPlaybackControlPort audioPlaybackControlPort(
            ConcurrentSessionRegistry concurrentSessionRegistry) {
        return new WebSocketPlaybackControlAdapter(concurrentSessionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public CallSessionService callSessionService(
            SessionRegistryPort sessionRegistryPort,
            AudioFramePublisherPort audioFramePublisherPort,
            AudioPlaybackControlPort audioPlaybackControlPort) {
        return new CallSessionService(
                sessionRegistryPort, audioFramePublisherPort, audioPlaybackControlPort);
    }
}
