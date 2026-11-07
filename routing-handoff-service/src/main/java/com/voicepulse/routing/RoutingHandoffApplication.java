package com.voicepulse.routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for VoicePulse Routing & Handoff Service.
 *
 * <p>Manages human operator presence in Redis, tiered skill queues, and disruption ticket handoff
 * persistence per RFC-0003.
 */
@SpringBootApplication
public class RoutingHandoffApplication {

    public static void main(String[] args) {
        SpringApplication.run(RoutingHandoffApplication.class, args);
    }
}
