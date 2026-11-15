package com.voicepulse.gateway.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GatewayBridgeProperties Fail-Fast Validation Tests")
class GatewayBridgePropertiesTest {

    @Test
    @DisplayName("accepts strictly positive outboundBufferCapacity")
    void acceptsValidConfiguration() {
        GatewayBridgeProperties props = new GatewayBridgeProperties(4096);
        assertThat(props.outboundBufferCapacity()).isEqualTo(4096);
    }

    @Test
    @DisplayName("fails fast on zero or negative outboundBufferCapacity")
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new GatewayBridgeProperties(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayBridgeProperties(-16))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
