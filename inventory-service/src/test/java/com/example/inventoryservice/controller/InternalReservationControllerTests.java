package com.example.inventoryservice.controller;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ConflictException;
import com.example.inventoryservice.dto.CreateReservationRequest;
import com.example.inventoryservice.dto.ReleaseReservationRequest;
import com.example.inventoryservice.dto.ReservationItemRequest;
import com.example.inventoryservice.service.ReservationItemCommand;
import com.example.inventoryservice.service.ReservationResult;
import com.example.inventoryservice.service.ReservationService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
class InternalReservationControllerTests {

    private static final String ORDER_SERVICE = "order-service";
    private static final String ORDER_SERVICE_KEY = "order-service-secret-key-32chars";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReservationService reservationService;

    @Test
    @DisplayName("Positive: Order service can create reservation successfully")
    void testCreateReservation_AsOrderService_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        CreateReservationRequest req = new CreateReservationRequest(
                orderId,
                List.of(new ReservationItemRequest(skuId, 2))
        );

        ReservationResult result = new ReservationResult(
                UUID.randomUUID(),
                orderId,
                "PENDING",
                Instant.now().plusSeconds(900),
                List.of(new ReservationItemCommand(skuId, 2))
        );
        when(reservationService.reserve(eq(orderId), any(), any())).thenReturn(result);

        mockMvc.perform(post("/internal/inventory/reservations")
                        .header("X-Calling-Service", ORDER_SERVICE)
                        .header("X-Internal-Service-Key", ORDER_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));
    }

    @Test
    @DisplayName("Positive: Order service can release reservation successfully")
    void testReleaseReservation_AsOrderService_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        ReleaseReservationRequest req = new ReleaseReservationRequest("Order cancelled by buyer");

        ReservationResult result = new ReservationResult(
                UUID.randomUUID(),
                orderId,
                "RELEASED",
                Instant.now(),
                List.of()
        );
        when(reservationService.release(eq(orderId), eq("Order cancelled by buyer"), any())).thenReturn(result);

        mockMvc.perform(post("/internal/inventory/reservations/" + orderId + "/release")
                        .header("X-Calling-Service", ORDER_SERVICE)
                        .header("X-Internal-Service-Key", ORDER_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("RELEASED"));
    }

    @Test
    @DisplayName("Negative: Anonymous request to internal endpoint returns 401 Unauthorized")
    void testCreateReservation_Anonymous_Unauthorized() throws Exception {
        CreateReservationRequest req = new CreateReservationRequest(
                UUID.randomUUID(),
                List.of(new ReservationItemRequest(UUID.randomUUID(), 1))
        );

        mockMvc.perform(post("/internal/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("Negative: Non-order service role returns 403 Forbidden")
    void testCreateReservation_WrongRole_Forbidden() throws Exception {
        CreateReservationRequest req = new CreateReservationRequest(
                UUID.randomUUID(),
                List.of(new ReservationItemRequest(UUID.randomUUID(), 1))
        );

        mockMvc.perform(post("/internal/inventory/reservations")
                        .header("X-Calling-Service", "payment-service")
                        .header("X-Internal-Service-Key", "payment-service-secret-key-32ch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Negative: Empty items list fails validation with 400 Bad Request")
    void testCreateReservation_EmptyItems_BadRequest() throws Exception {
        CreateReservationRequest req = new CreateReservationRequest(UUID.randomUUID(), List.of());

        mockMvc.perform(post("/internal/inventory/reservations")
                        .header("X-Calling-Service", ORDER_SERVICE)
                        .header("X-Internal-Service-Key", ORDER_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("Negative: Insufficient inventory returns 409 Conflict")
    void testCreateReservation_InsufficientInventory_Conflict() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        CreateReservationRequest req = new CreateReservationRequest(
                orderId,
                List.of(new ReservationItemRequest(skuId, 10))
        );

        when(reservationService.reserve(eq(orderId), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT, "Stock insufficient for sku: " + skuId));

        mockMvc.perform(post("/internal/inventory/reservations")
                        .header("X-Calling-Service", ORDER_SERVICE)
                        .header("X-Internal-Service-Key", ORDER_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_INSUFFICIENT"));
    }

    @Test
    @DisplayName("Negative: State conflict returns 409 Conflict")
    void testCreateReservation_StateConflict_Conflict() throws Exception {
        UUID orderId = UUID.randomUUID();
        CreateReservationRequest req = new CreateReservationRequest(
                orderId,
                List.of(new ReservationItemRequest(UUID.randomUUID(), 1))
        );

        when(reservationService.reserve(eq(orderId), any(), any()))
                .thenThrow(new ConflictException(ErrorCode.STATE_CONFLICT, "Reservation already exists with different items"));

        mockMvc.perform(post("/internal/inventory/reservations")
                        .header("X-Calling-Service", ORDER_SERVICE)
                        .header("X-Internal-Service-Key", ORDER_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }
}
