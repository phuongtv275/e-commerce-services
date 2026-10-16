package com.example.configserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("native")
@TestPropertySource(properties = "eureka.client.enabled=false")
class ConfigServerApplicationTests {

    @Test
    void contextLoads() {
    }

}
