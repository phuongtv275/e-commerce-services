package com.example.catalogservice.product;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.InternalBatchSkuRequest;
import com.example.catalogservice.dto.resp.InternalBatchSkuResponse;
import com.example.catalogservice.dto.resp.InternalSkuResponse;
import com.example.catalogservice.service.SkuService;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
        "management.health.elasticsearch.enabled=false",
        "security.internal-service-keys.order-service=order-service-secret-key-32chars",
        "security.internal-service-keys.notification-service=notification-service-secret-key-32c",
        "security.gateway-assertion.public-key=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAr+qpeFC7N9yYQYKyKYd3wWHnhth0aithJSysJifsaz1k3n0QKMOejmJxK8BrGwYGSH7tCjlRkOUPG5h4jWgGuv3CgnCJR8hzSx362wYkcykI4e0Umjya3ebd2ArPKN0NgZ0xfIphtsS+OQCKFOF5FcZr/T14qP62Dtr+8K91VltTiNbOTxrGxBLfhxf9CZ9iSYZy3xnQW2lwL+wblp2ps8qOBLvCp8QP81Ffa7M8IM6qTX24g5FnmCub/jPJ/9oqJNFvRw5hRHQvNLvY72P9a+nN4Jg4N36A2djbAR9Ws4kWkB4Inrbwg/kVrxWafrPoXCZG+aPcF3pbJ1Sz333EFwIDAQAB"
})
class InternalSkuControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SkuService skuService;

    private static final String ORDER_SERVICE = "order-service";
    private static final String ORDER_KEY = "order-service-secret-key-32chars";
    private static final String NOTIF_SERVICE = "notification-service";
    private static final String NOTIF_KEY = "notification-service-secret-key-32c";

    @Test
    @DisplayName("Positive: GET /internal/catalog/skus/{skuId} with order-service internal headers returns 200 OK")
    void testGetInternalSku_OrderServiceAllowed() throws Exception {
        UUID skuId = UUID.randomUUID();
        InternalSkuResponse response = InternalSkuResponse.builder()
                .skuId(skuId)
                .skuCode("SKU-100")
                .productId(UUID.randomUUID())
                .productName("Sample Product")
                .skuName("Sample SKU")
                .price(new BigDecimal("100000"))
                .currency("VND")
                .status("ACTIVE")
                .updatedAt(Instant.now())
                .build();

        when(skuService.getInternalSku(skuId)).thenReturn(response);

        mockMvc.perform(get("/internal/catalog/skus/{skuId}", skuId)
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, ORDER_SERVICE)
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, ORDER_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.skuCode").value("SKU-100"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("Positive: POST /internal/catalog/skus/batch with order-service internal headers returns 200 OK")
    void testGetInternalBatchSkus_OrderServiceAllowed() throws Exception {
        UUID skuId1 = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();

        InternalBatchSkuRequest req = InternalBatchSkuRequest.builder()
                .skuIds(List.of(skuId1, missingId))
                .build();

        InternalBatchSkuResponse response = InternalBatchSkuResponse.builder()
                .items(List.of(InternalSkuResponse.builder().skuId(skuId1).skuCode("SKU-1").build()))
                .missingSkuIds(List.of(missingId))
                .build();

        when(skuService.getInternalBatchSkus(any())).thenReturn(response);

        mockMvc.perform(post("/internal/catalog/skus/batch")
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, ORDER_SERVICE)
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, ORDER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].skuCode").value("SKU-1"))
                .andExpect(jsonPath("$.data.missingSkuIds[0]").value(missingId.toString()));
    }

    @Test
    @DisplayName("Negative: GET /internal/catalog/skus/{skuId} without auth headers returns 401")
    void testGetInternalSku_Unauthenticated() throws Exception {
        UUID skuId = UUID.randomUUID();

        mockMvc.perform(get("/internal/catalog/skus/{skuId}", skuId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
    }

    @Test
    @DisplayName("Negative: GET /internal/catalog/skus/{skuId} with Gateway assertion returns 403 (blocked)")
    void testGetInternalSku_GatewayAssertionBlocked() throws Exception {
        UUID skuId = UUID.randomUUID();

        mockMvc.perform(get("/internal/catalog/skus/{skuId}", skuId)
                        .header(SecurityConstants.HEADER_GATEWAY_ASSERTION, "some-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Negative: GET /internal/catalog/skus/{skuId} with notification-service caller returns 403 (requires SERVICE_ORDER)")
    void testGetInternalSku_NotificationServiceForbidden() throws Exception {
        UUID skuId = UUID.randomUUID();

        mockMvc.perform(get("/internal/catalog/skus/{skuId}", skuId)
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, NOTIF_SERVICE)
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, NOTIF_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
    }

    @Test
    @DisplayName("Negative: GET /internal/catalog/skus/{skuId} for non-existent SKU returns 404")
    void testGetInternalSku_NotFound() throws Exception {
        UUID skuId = UUID.randomUUID();
        when(skuService.getInternalSku(skuId)).thenThrow(new ResourceNotFoundException("SKU not found with id: " + skuId));

        mockMvc.perform(get("/internal/catalog/skus/{skuId}", skuId)
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, ORDER_SERVICE)
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, ORDER_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("Negative: POST /internal/catalog/skus/batch with empty list returns 400")
    void testGetInternalBatchSkus_ValidationFailure() throws Exception {
        InternalBatchSkuRequest req = InternalBatchSkuRequest.builder()
                .skuIds(List.of())
                .build();

        mockMvc.perform(post("/internal/catalog/skus/batch")
                        .header(SecurityConstants.HEADER_CALLING_SERVICE, ORDER_SERVICE)
                        .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, ORDER_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.name()));
    }
}
