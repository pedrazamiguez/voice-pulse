package com.voicepulse.routing.infrastructure.ws.config;

import com.voicepulse.routing.infrastructure.ws.OperatorConsoleWebSocketHandler;
import java.net.URI;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/**
 * Spring WebFlux configuration registering the Operator Web Console WebSocket handler mapping
 * ({@code /ws/operators/{operatorId}}), adapter, and non-blocking static UI redirects.
 */
@Configuration
@EnableConfigurationProperties(OperatorConsoleProperties.class)
public class OperatorWebConfiguration {

    public static final String OPERATOR_WS_PATH = "/ws/operators/{operatorId}";
    private static final URI CONSOLE_INDEX_URI = URI.create("/console/index.html");

    @Bean
    public HandlerMapping operatorWebSocketHandlerMapping(
            OperatorConsoleWebSocketHandler operatorConsoleWebSocketHandler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of(OPERATOR_WS_PATH, operatorConsoleWebSocketHandler));
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return mapping;
    }

    @Bean
    @ConditionalOnMissingBean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }

    @Bean
    public RouterFunction<ServerResponse> operatorConsoleRoutes() {
        return RouterFunctions.route()
                .GET("/", request -> ServerResponse.temporaryRedirect(CONSOLE_INDEX_URI).build())
                .GET(
                        "/console",
                        request -> ServerResponse.temporaryRedirect(CONSOLE_INDEX_URI).build())
                .GET(
                        "/console/",
                        request -> ServerResponse.temporaryRedirect(CONSOLE_INDEX_URI).build())
                .build();
    }
}
