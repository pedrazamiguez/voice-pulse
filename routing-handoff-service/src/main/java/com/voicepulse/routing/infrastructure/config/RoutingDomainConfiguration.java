package com.voicepulse.routing.infrastructure.config;

import com.voicepulse.routing.domain.ports.outbound.AudioRerouteSignalPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorDispatchPort;
import com.voicepulse.routing.domain.ports.outbound.OperatorPresencePort;
import com.voicepulse.routing.domain.ports.outbound.PriorityCallQueuePort;
import com.voicepulse.routing.domain.ports.outbound.TicketPersistencePort;
import com.voicepulse.routing.domain.service.HandoffCompletionService;
import com.voicepulse.routing.domain.service.HandoffPersistenceCoordinator;
import com.voicepulse.routing.domain.service.OperatorAssignmentCoordinator;
import com.voicepulse.routing.domain.service.OperatorPresenceService;
import com.voicepulse.routing.domain.service.OperatorRerouteDispatcher;
import com.voicepulse.routing.domain.service.QueuedCallClaimService;
import com.voicepulse.routing.domain.service.QueuedCallDrainService;
import com.voicepulse.routing.domain.service.RoutingQueueQueryService;
import com.voicepulse.routing.domain.service.SkillGroupMatchingPolicy;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration wiring pure domain routing policies, workers, and use-case services with
 * infrastructure outbound ports via strict constructor dependency inversion.
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
    public HandoffPersistenceCoordinator handoffPersistenceCoordinator(
            TicketPersistencePort ticketPersistencePort,
            OperatorPresencePort operatorPresencePort,
            PriorityCallQueuePort priorityCallQueuePort,
            Clock routingClock) {
        return new HandoffPersistenceCoordinator(
                ticketPersistencePort, operatorPresencePort, priorityCallQueuePort, routingClock);
    }

    @Bean
    @ConditionalOnMissingBean
    public OperatorRerouteDispatcher operatorRerouteDispatcher(
            OperatorDispatchPort operatorDispatchPort,
            AudioRerouteSignalPort audioRerouteSignalPort,
            RoutingProperties routingProperties) {
        return new OperatorRerouteDispatcher(
                operatorDispatchPort,
                audioRerouteSignalPort,
                routingProperties.operatorBridgeBasePath());
    }

    @Bean
    @ConditionalOnMissingBean
    public QueuedCallDrainService queuedCallDrainService(
            OperatorPresencePort operatorPresencePort,
            PriorityCallQueuePort priorityCallQueuePort,
            HandoffPersistenceCoordinator handoffPersistenceCoordinator,
            OperatorRerouteDispatcher operatorRerouteDispatcher) {
        return new QueuedCallDrainService(
                operatorPresencePort,
                priorityCallQueuePort,
                handoffPersistenceCoordinator,
                operatorRerouteDispatcher);
    }

    @Bean
    @ConditionalOnMissingBean
    public OperatorAssignmentCoordinator operatorAssignmentCoordinator(
            SkillGroupMatchingPolicy skillGroupMatchingPolicy,
            OperatorPresencePort operatorPresencePort,
            PriorityCallQueuePort priorityCallQueuePort,
            HandoffPersistenceCoordinator handoffPersistenceCoordinator,
            OperatorRerouteDispatcher operatorRerouteDispatcher,
            Clock routingClock) {
        return new OperatorAssignmentCoordinator(
                skillGroupMatchingPolicy,
                operatorPresencePort,
                priorityCallQueuePort,
                handoffPersistenceCoordinator,
                operatorRerouteDispatcher,
                routingClock);
    }

    @Bean
    @ConditionalOnMissingBean
    public OperatorPresenceService operatorPresenceService(
            OperatorPresencePort operatorPresencePort,
            QueuedCallDrainService queuedCallDrainService) {
        return new OperatorPresenceService(operatorPresencePort, queuedCallDrainService);
    }

    @Bean
    @ConditionalOnMissingBean
    public QueuedCallClaimService queuedCallClaimService(
            PriorityCallQueuePort priorityCallQueuePort,
            QueuedCallDrainService queuedCallDrainService) {
        return new QueuedCallClaimService(priorityCallQueuePort, queuedCallDrainService);
    }

    @Bean
    @ConditionalOnMissingBean
    public HandoffCompletionService handoffCompletionService(
            OperatorPresencePort operatorPresencePort,
            PriorityCallQueuePort priorityCallQueuePort,
            HandoffPersistenceCoordinator handoffPersistenceCoordinator,
            OperatorRerouteDispatcher operatorRerouteDispatcher,
            QueuedCallDrainService queuedCallDrainService) {
        return new HandoffCompletionService(
                operatorPresencePort,
                priorityCallQueuePort,
                handoffPersistenceCoordinator,
                operatorRerouteDispatcher,
                queuedCallDrainService);
    }

    @Bean
    @ConditionalOnMissingBean
    public RoutingQueueQueryService routingQueueQueryService(
            PriorityCallQueuePort priorityCallQueuePort,
            TicketPersistencePort ticketPersistencePort) {
        return new RoutingQueueQueryService(priorityCallQueuePort, ticketPersistencePort);
    }
}
