package com.voicepulse.routing.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link RoutingProperties} for fail-fast binding at startup. */
@Configuration
@EnableConfigurationProperties(RoutingProperties.class)
public class RoutingConfiguration {}
