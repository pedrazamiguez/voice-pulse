package com.voicepulse.routing.infrastructure.kafka.config;

import com.voicepulse.routing.infrastructure.config.RoutingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link RoutingKafkaProperties} and {@link RoutingProperties} for fail-fast Kafka
 * configuration binding.
 */
@Configuration
@EnableConfigurationProperties({RoutingKafkaProperties.class, RoutingProperties.class})
public class RoutingKafkaConfiguration {}
