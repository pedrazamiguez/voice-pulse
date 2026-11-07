package com.voicepulse.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class EdgeGatewayApplicationTests {

    @Test
    @DisplayName("edge gateway application context loads cleanly")
    void contextLoads() {}
}
