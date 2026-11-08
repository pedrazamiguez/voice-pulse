package com.voicepulse.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.voicepulse.gateway.infrastructure.ws.AudioWebSocketHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

@SpringBootTest
@ActiveProfiles("test")
class EdgeGatewayApplicationTests {

    private final ApplicationContext applicationContext;

    @Autowired
    EdgeGatewayApplicationTests(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Test
    @DisplayName("edge gateway application context loads cleanly with WebSocket beans")
    void contextLoads() {
        assertThat(applicationContext.getBean(AudioWebSocketHandler.class)).isNotNull();
        assertThat(applicationContext.getBean("webSocketHandlerMapping", HandlerMapping.class))
                .isNotNull();
        assertThat(applicationContext.getBeansOfType(WebSocketHandlerAdapter.class)).isNotEmpty();
    }
}
