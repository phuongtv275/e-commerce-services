package com.example.inventoryservice.controller;

import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.inventoryservice.dto.StockAdjustmentRequest;
import com.example.inventoryservice.service.StockAdjustmentCommand;
import com.example.inventoryservice.service.StockBalance;
import com.example.inventoryservice.service.StockMovementView;
import com.example.inventoryservice.service.StockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class StockControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StockService stockService;

    @Test
    @DisplayName("Positive: Staff can adjust stock successfully")
    void testAdjustStock_AsStaff_Success() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        StockAdjustmentRequest req = new StockAdjustmentRequest(warehouseId, 10, "ref-test-1", "Restock inventory");

        StockBalance balance = new StockBalance(skuId, warehouseId, 20, 0, 20, 1L, Instant.now());
        when(stockService.adjust(any(StockAdjustmentCommand.class), any(), any())).thenReturn(balance);

        mockMvc.perform(post("/api/v1/inventory/stocks/" + skuId + "/adjust")
                        .with(user("staffUser").roles("INVENTORY_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.onHand").value(20))
                .andExpect(jsonPath("$.data.available").value(20));
    }

    @Test
    @DisplayName("Positive: Authenticated user can query stock balance")
    void testGetStock_Authenticated_Success() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        StockBalance balance = new StockBalance(skuId, warehouseId, 15, 5, 10, 2L, Instant.now());
        when(stockService.get(eq(skuId), any())).thenReturn(balance);

        mockMvc.perform(get("/api/v1/inventory/stocks/" + skuId)
                        .with(user("regularUser").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.onHand").value(15))
                .andExpect(jsonPath("$.data.reserved").value(5))
                .andExpect(jsonPath("$.data.available").value(10));
    }

    @Test
    @DisplayName("Positive: Staff can query movements with pagination")
    void testGetMovements_AsStaff_Success() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        StockMovementView view = new StockMovementView(
                UUID.randomUUID(), skuId, warehouseId, "INBOUND", 10, "ADJUSTMENT", "ref-1",
                0, 10, 0, 0, "staffUser", "Initial", "corr-1", Instant.now()
        );
        when(stockService.movements(eq(skuId), any(), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/inventory/stocks/" + skuId + "/movements")
                        .with(user("staffUser").roles("INVENTORY_STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].movementType").value("INBOUND"))
                .andExpect(jsonPath("$.data.content[0].quantityDelta").value(10));
    }

    @Test
    @DisplayName("Negative: Customer role is forbidden from adjusting stock")
    void testAdjustStock_AsCustomer_Forbidden() throws Exception {
        UUID skuId = UUID.randomUUID();
        StockAdjustmentRequest req = new StockAdjustmentRequest(null, 10, "ref-cust", "Reason");

        mockMvc.perform(post("/api/v1/inventory/stocks/" + skuId + "/adjust")
                        .with(user("custUser").roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Negative: Anonymous request to get stock returns 401 Unauthorized")
    void testGetStock_Anonymous_Unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/stocks/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("Negative: Non-existent SKU returns 404 Not Found")
    void testGetStock_NotFound() throws Exception {
        UUID skuId = UUID.randomUUID();
        when(stockService.get(eq(skuId), any())).thenThrow(new ResourceNotFoundException("Stock not found for SKU: " + skuId));

        mockMvc.perform(get("/api/v1/inventory/stocks/" + skuId)
                        .with(user("regularUser").roles("CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Negative: Customer role is forbidden from reading movements")
    void testGetMovements_AsCustomer_Forbidden() throws Exception {
        UUID skuId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/inventory/stocks/" + skuId + "/movements")
                        .with(user("custUser").roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Negative: Invalid adjustment body fails validation with 400 Bad Request")
    void testAdjustStock_InvalidBody_BadRequest() throws Exception {
        UUID skuId = UUID.randomUUID();
        StockAdjustmentRequest invalidReq = new StockAdjustmentRequest(null, null, "", "");

        mockMvc.perform(post("/api/v1/inventory/stocks/" + skuId + "/adjust")
                        .with(user("staffUser").roles("INVENTORY_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
