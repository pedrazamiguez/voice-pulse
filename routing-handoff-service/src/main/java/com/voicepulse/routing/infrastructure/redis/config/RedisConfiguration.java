package com.voicepulse.routing.infrastructure.redis.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link RedisRoutingProperties} for fail-fast Redis configuration binding. */
@Configuration
@EnableConfigurationProperties(RedisRoutingProperties.class)
public class RedisConfiguration {}
