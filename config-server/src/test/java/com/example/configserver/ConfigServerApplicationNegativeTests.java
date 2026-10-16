package com.example.configserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("native")
@org.springframework.test.context.TestPropertySource(properties = "eureka.client.enabled=false")
class ConfigServerApplicationNegativeTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Negative: Config fetch for unknown service should not find service-specific property source")
    void testConfigFetchForUnknownServiceReturnsNoServiceSpecificConfig() throws Exception {
        mockMvc.perform(get("/non-existent-service-xyz/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("non-existent-service-xyz"))
                .andExpect(jsonPath("$.propertySources[*].name", not(containsString("non-existent-service-xyz.yml"))));
    }
}
