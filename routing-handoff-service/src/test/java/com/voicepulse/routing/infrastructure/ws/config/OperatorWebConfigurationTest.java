package com.voicepulse.routing.infrastructure.ws.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.voicepulse.routing.infrastructure.ws.OperatorConsoleWebSocketHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

@DisplayName("OperatorWebConfiguration Tests")
class OperatorWebConfigurationTest {

    private final OperatorWebConfiguration configuration = new OperatorWebConfiguration();

    @Test
    @DisplayName("Registers /ws/operators/{operatorId} with highest precedence")
    void registersWebSocketMapping() {
        OperatorConsoleWebSocketHandler handler = mock(OperatorConsoleWebSocketHandler.class);

        SimpleUrlHandlerMapping mapping =
                (SimpleUrlHandlerMapping) configuration.operatorWebSocketHandlerMapping(handler);

        assertThat(mapping.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(mapping.getUrlMap().get(OperatorWebConfiguration.OPERATOR_WS_PATH))
                .isSameAs(handler);
        assertThat(configuration.webSocketHandlerAdapter()).isNotNull();
    }

    @Test
    @DisplayName("Redirects root and /console paths cleanly to /console/index.html")
    void redirectsConsoleEntrypoints() {
        WebTestClient client =
                WebTestClient.bindToRouterFunction(configuration.operatorConsoleRoutes()).build();

        client.get()
                .uri("/")
                .exchange()
                .expectStatus()
                .isTemporaryRedirect()
                .expectHeader()
                .valueEquals("Location", "/console/index.html");

        client.get()
                .uri("/console")
                .exchange()
                .expectStatus()
                .isTemporaryRedirect()
                .expectHeader()
                .valueEquals("Location", "/console/index.html");

        client.get()
                .uri("/console/")
                .exchange()
                .expectStatus()
                .isTemporaryRedirect()
                .expectHeader()
                .valueEquals("Location", "/console/index.html");
    }
}
