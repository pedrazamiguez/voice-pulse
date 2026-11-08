package com.voicepulse.gateway.infrastructure.config;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** WebFlux routing configuration providing redirects to the browser softphone client UI. */
@Configuration
public class SoftphoneWebConfiguration {

    private static final URI SOFTPHONE_INDEX_URI = URI.create("/softphone/index.html");

    @Bean
    public RouterFunction<ServerResponse> softphoneRoutes() {
        return RouterFunctions.route()
                .GET("/", request -> ServerResponse.temporaryRedirect(SOFTPHONE_INDEX_URI).build())
                .GET(
                        "/softphone",
                        request -> ServerResponse.temporaryRedirect(SOFTPHONE_INDEX_URI).build())
                .GET(
                        "/softphone/",
                        request -> ServerResponse.temporaryRedirect(SOFTPHONE_INDEX_URI).build())
                .build();
    }
}
