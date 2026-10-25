package com.example.inventoryservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=${INVENTORY_TEST_DB_URL:jdbc:postgresql://localhost:5432/inventory_db}",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres"
})
class InventoryServiceNegativeTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Negative: Request real stock endpoint without authentication returns 401 Unauthorized")
    void testUnauthenticatedStockRequestReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/stocks/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }
}
