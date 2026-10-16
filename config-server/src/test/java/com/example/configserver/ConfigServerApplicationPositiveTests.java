package com.example.configserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("native")
@org.springframework.test.context.TestPropertySource(properties = "eureka.client.enabled=false")
class ConfigServerApplicationPositiveTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Positive: Health endpoint should return status UP")
    void testHealthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Positive: Config fetch for identity-service should return service-specific configuration")
    void testConfigFetchForKnownService() throws Exception {
        mockMvc.perform(get("/identity-service/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("identity-service"))
                .andExpect(jsonPath("$.propertySources").isNotEmpty())
                .andExpect(jsonPath("$.propertySources[*].name", hasItem(containsString("identity-service.yml"))))
                .andExpect(jsonPath("$.propertySources[?(@.name =~ /.*identity-service\\.ya?ml.*/)].source['server.port']").value(8081));
    }
}
