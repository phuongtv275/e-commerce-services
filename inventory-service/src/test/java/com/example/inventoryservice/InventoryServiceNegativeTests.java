package com.example.inventoryservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class InventoryServiceNegativeTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Negative: Non-existent endpoint should return 404 Not Found")
    void testNonExistentEndpointReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/non-existent-resource"))
                .andExpect(status().isNotFound());
    }
}
