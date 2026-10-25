package com.example.inventoryservice.controller;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.constant.SecurityConstants;
import com.example.inventoryservice.dto.BatchStockRequest;
import com.example.inventoryservice.dto.BatchStockResponse;
import com.example.inventoryservice.dto.SkuStockAvailability;
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

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
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
class InternalStockControllerTests {

    private static final String ORDER_SERVICE = "order-service";
    private static final String ORDER_SERVICE_KEY = "order-service-secret-key-32chars";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StockService stockService;

    @Test
    @DisplayName("POS-STOCK-01: Order service calls batch availability with valid key and known SKUs -> 200 OK")
    void testGetBatchStockAvailability_AllKnown_Success() throws Exception {
        UUID skuId1 = UUID.randomUUID();
        UUID skuId2 = UUID.randomUUID();

        BatchStockRequest request = new BatchStockRequest(List.of(skuId1, skuId2));
        BatchStockResponse response = BatchStockResponse.builder()
                .items(List.of(
                        SkuStockAvailability.builder().skuId(skuId1).availableQuantity(25).build(),
                        SkuStockAvailability.builder().skuId(skuId2).availableQuantity(10).build()
                ))
                .missingSkuIds(List.of())
                .build();

        when(stockService.getBatchStockAvailability(any())).thenReturn(response);

        mockMvc.perform(post("/internal/inventory/stocks/batch")
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, ORDER_SERVICE)
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, ORDER_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].skuId").value(skuId1.toString()))
                .andExpect(jsonPath("$.data.items[0].availableQuantity").value(25))
                .andExpect(jsonPath("$.data.items[1].skuId").value(skuId2.toString()))
                .andExpect(jsonPath("$.data.items[1].availableQuantity").value(10))
                .andExpect(jsonPath("$.data.missingSkuIds").isEmpty());
    }

    @Test
    @DisplayName("POS-STOCK-02: Mixed known and unknown SKUs -> 200 OK with missing SKU IDs explicitly listed")
    void testGetBatchStockAvailability_MixedKnownAndUnknown_Success() throws Exception {
        UUID knownSku = UUID.randomUUID();
        UUID unknownSku = UUID.randomUUID();

        BatchStockRequest request = new BatchStockRequest(List.of(knownSku, unknownSku));
        BatchStockResponse response = BatchStockResponse.builder()
                .items(List.of(
                        SkuStockAvailability.builder().skuId(knownSku).availableQuantity(5).build()
                ))
                .missingSkuIds(List.of(unknownSku))
                .build();

        when(stockService.getBatchStockAvailability(any())).thenReturn(response);

        mockMvc.perform(post("/internal/inventory/stocks/batch")
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, ORDER_SERVICE)
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, ORDER_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].skuId").value(knownSku.toString()))
                .andExpect(jsonPath("$.data.items[0].availableQuantity").value(5))
                .andExpect(jsonPath("$.data.missingSkuIds[0]").value(unknownSku.toString()));
    }

    @Test
    @DisplayName("NEG-STOCK-01: Empty skuIds list -> 400 Bad Request (VALIDATION_ERROR)")
    void testGetBatchStockAvailability_EmptyList_BadRequest() throws Exception {
        BatchStockRequest request = new BatchStockRequest(Collections.emptyList());

        mockMvc.perform(post("/internal/inventory/stocks/batch")
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, ORDER_SERVICE)
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, ORDER_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.name()));
    }

    @Test
    @DisplayName("NEG-STOCK-02: Oversized skuIds list (> 100) -> 400 Bad Request (VALIDATION_ERROR)")
    void testGetBatchStockAvailability_OversizedList_BadRequest() throws Exception {
        List<UUID> oversizedList = IntStream.range(0, 101)
                .mapToObj(i -> UUID.randomUUID())
                .toList();
        BatchStockRequest request = new BatchStockRequest(oversizedList);

        mockMvc.perform(post("/internal/inventory/stocks/batch")
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, ORDER_SERVICE)
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, ORDER_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.name()));
    }

    @Test
    @DisplayName("NEG-STOCK-03: Missing internal auth headers -> 401 Unauthorized (AUTHENTICATION_REQUIRED)")
    void testGetBatchStockAvailability_MissingHeaders_Unauthorized() throws Exception {
        BatchStockRequest request = new BatchStockRequest(List.of(UUID.randomUUID()));

        mockMvc.perform(post("/internal/inventory/stocks/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }

    @Test
    @DisplayName("NEG-STOCK-04: Non-order-service role (e.g. payment-service) -> 403 Forbidden (ACCESS_DENIED)")
    void testGetBatchStockAvailability_PaymentServiceRole_Forbidden() throws Exception {
        BatchStockRequest request = new BatchStockRequest(List.of(UUID.randomUUID()));

        mockMvc.perform(post("/internal/inventory/stocks/batch")
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, "payment-service")
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "payment-service-secret-key-32ch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("NEG-STOCK-05: Invalid internal service key -> 403 Forbidden (ACCESS_DENIED)")
    void testGetBatchStockAvailability_InvalidKey_Forbidden() throws Exception {
        BatchStockRequest request = new BatchStockRequest(List.of(UUID.randomUUID()));

        mockMvc.perform(post("/internal/inventory/stocks/batch")
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, ORDER_SERVICE)
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "invalid-key-12345678901234567890")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("NEG-STOCK-06: Gateway assertion blocked on internal endpoint -> 403 Forbidden (ACCESS_DENIED)")
    void testGetBatchStockAvailability_GatewayAssertionBlocked_Forbidden() throws Exception {
        BatchStockRequest request = new BatchStockRequest(List.of(UUID.randomUUID()));

        mockMvc.perform(post("/internal/inventory/stocks/batch")
                        .header(SecurityConstants.HEADER_GATEWAY_ASSERTION, "forged-jwt-token")
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, ORDER_SERVICE)
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, ORDER_SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }
}
