package com.voicepulse.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for VoicePulse Edge Gateway Service.
 *
 * <p>Provides reactive WebFlux and Netty WebSocket endpoints for high-frequency binary audio
 * streaming, VAD ingestion, and softphone signaling.
 */
@SpringBootApplication
public class EdgeGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdgeGatewayApplication.class, args);
    }
}
