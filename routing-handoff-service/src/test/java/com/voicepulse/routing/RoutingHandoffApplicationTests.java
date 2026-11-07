package com.voicepulse.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RoutingHandoffApplicationTests {

    @Test
    @DisplayName("routing handoff application context loads cleanly")
    void contextLoads() {}
}
