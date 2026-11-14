package com.voicepulse.routing.infrastructure.config;

import com.voicepulse.routing.domain.ports.outbound.AudioRerouteSignalPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorDispatchPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
import com.voicepulse.routing.domain.service.OperatorAssignmentCoordinator;
import com.voicepulse.routing.domain.service.SkillGroupMatchingPolicy;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration wiring pure domain routing policies and coordinators with infrastructure
 * outbound ports via strict constructor dependency inversion.
 */
@Configuration
@EnableConfigurationProperties(RoutingProperties.class)
public class RoutingDomainConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock routingClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillGroupMatchingPolicy skillGroupMatchingPolicy() {
        return new SkillGroupMatchingPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public OperatorAssignmentCoordinator operatorAssignmentCoordinator(
            SkillGroupMatchingPolicy skillGroupMatchingPolicy,
            TicketPersistencePort ticketPersistencePort,
            OperatorPresencePort operatorPresencePort,
            PriorityCallQueuePort priorityCallQueuePort,
            OperatorDispatchPort operatorDispatchPort,
            AudioRerouteSignalPort audioRerouteSignalPort,
            RoutingProperties routingProperties,
            Clock routingClock) {
        return new OperatorAssignmentCoordinator(
                skillGroupMatchingPolicy,
                ticketPersistencePort,
                operatorPresencePort,
                priorityCallQueuePort,
                operatorDispatchPort,
                audioRerouteSignalPort,
                routingProperties.operatorBridgeBasePath(),
                routingClock);
    }
}
