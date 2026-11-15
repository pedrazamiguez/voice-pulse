package com.voicepulse.gateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fail-fast configuration properties controlling the outbound buffer capacity of human operator
 * WebSocket audio bridges.
 */
@ConfigurationProperties(prefix = "voicepulse.gateway.bridge")
public record GatewayBridgeProperties(int outboundBufferCapacity) {

    public GatewayBridgeProperties {
        if (outboundBufferCapacity <= 0) {
            throw new IllegalArgumentException(
                    "voicepulse.gateway.bridge.outbound-buffer-capacity must be positive, got: "
                            + outboundBufferCapacity);
        }
    }
}
