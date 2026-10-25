package com.example.inventoryservice.security;

import com.ecommerce.common.constant.SecurityConstants;
import com.example.inventoryservice.dto.StockAdjustmentRequest;
import com.example.inventoryservice.service.ReservationResult;
import com.example.inventoryservice.service.ReservationService;
import com.example.inventoryservice.service.StockBalance;
import com.example.inventoryservice.service.StockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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
        "spring.datasource.password=postgres",
        "security.internal-service-keys.order-service=order-service-secret-key-32chars",
        "security.internal-service-keys.payment-service=payment-service-secret-key-32ch"
})
class SecurityConfigTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StockService stockService;

    @MockitoBean
    private ReservationService reservationService;

    @Test
    @DisplayName("Positive: Actuator health endpoint is public without authentication")
    void testActuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Positive: Internal service call with correct order-service key succeeds")
    void testInternalServiceAuth_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(reservationService.reserve(any(), any(), any())).thenReturn(new ReservationResult(
                UUID.randomUUID(), orderId, "PENDING", Instant.now(), List.of()
        ));

        String body = """
                {
                    "orderId": "%s",
                    "items": [{"skuId": "%s", "quantity": 1}]
                }
                """.formatted(orderId, UUID.randomUUID());

        mockMvc.perform(post("/internal/inventory/reservations")
                        .header("X-Calling-Service", "order-service")
                        .header("X-Internal-Service-Key", "order-service-secret-key-32chars")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("Negative: Internal service call with wrong key returns 403 Forbidden")
    void testInternalServiceAuth_WrongKey_Forbidden() throws Exception {
        String body = """
                {
                    "orderId": "%s",
                    "items": [{"skuId": "%s", "quantity": 1}]
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/internal/inventory/reservations")
                        .header("X-Calling-Service", "order-service")
                        .header("X-Internal-Service-Key", "wrong-secret-key-wrong-length")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Negative: Internal service call with wrong calling service role returns 403 Forbidden")
    void testInternalServiceAuth_WrongServiceRole_Forbidden() throws Exception {
        String body = """
                {
                    "orderId": "%s",
                    "items": [{"skuId": "%s", "quantity": 1}]
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        // payment-service passes key check, but gets ROLE_SERVICE_PAYMENT which is not ROLE_SERVICE_ORDER
        mockMvc.perform(post("/internal/inventory/reservations")
                        .header("X-Calling-Service", "payment-service")
                        .header("X-Internal-Service-Key", "payment-service-secret-key-32ch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Positive: Staff user role can adjust stock")
    void testStaffRole_AdjustStock_Success() throws Exception {
        UUID skuId = UUID.randomUUID();
        StockAdjustmentRequest req = new StockAdjustmentRequest(null, 5, "ref-trusted", "Staff restock");

        when(stockService.adjust(any(), any(), any())).thenReturn(
                new StockBalance(skuId, UUID.randomUUID(), 10, 0, 10, 1L, Instant.now())
        );

        mockMvc.perform(post("/api/v1/inventory/stocks/" + skuId + "/adjust")
                        .with(user("staff-user").roles("INVENTORY_STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("Negative: Customer user role cannot adjust stock")
    void testCustomerRole_AdjustStock_Forbidden() throws Exception {
        UUID skuId = UUID.randomUUID();
        StockAdjustmentRequest req = new StockAdjustmentRequest(null, 5, "ref-trusted", "Customer try");

        mockMvc.perform(post("/api/v1/inventory/stocks/" + skuId + "/adjust")
                        .with(user("customer-user").roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
