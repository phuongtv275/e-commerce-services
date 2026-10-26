package com.example.notificationservice.service;

import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.response.ApiResponse;
import com.example.notificationservice.dto.internal.InternalOrderDto;
import com.example.notificationservice.dto.internal.InternalUserDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalClientServiceTests {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private InternalClientService internalClientService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.readTimeout(any())).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);

        internalClientService = new InternalClientService(
                builder,
                "http://identity-service:8081",
                "identity-secret-key",
                3000L,
                5000L,
                "http://order-service:8084",
                "order-secret-key",
                3000L,
                5000L
        );
    }

    @Test
    @DisplayName("Positive: Successfully get user from internal Identity Service")
    void testGetUser_Success() throws Exception {
        UUID userId = UUID.randomUUID();
        InternalUserDto userDto = InternalUserDto.builder()
                .id(userId)
                .email("test@example.com")
                .fullName("Nguyễn Văn A")
                .build();
        ApiResponse<InternalUserDto> apiResponse = ApiResponse.success(userDto);

        mockServer.expect(MockRestRequestMatchers.requestTo("http://identity-service:8081/internal/users/" + userId))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header(SecurityConstants.HEADER_CALLING_SERVICE, "notification-service"))
                .andExpect(MockRestRequestMatchers.header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "identity-secret-key"))
                .andRespond(MockRestResponseCreators.withSuccess(objectMapper.writeValueAsString(apiResponse), MediaType.APPLICATION_JSON));

        InternalUserDto result = internalClientService.getUser(userId);

        assertNotNull(result);
        assertEquals(userId, result.getId());
        assertEquals("test@example.com", result.getEmail());
        mockServer.verify();
    }

    @Test
    @DisplayName("Positive: Successfully get order notification snapshot from internal Order Service")
    void testGetOrder_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        InternalOrderDto orderDto = InternalOrderDto.builder()
                .orderId(orderId)
                .customerId(customerId)
                .orderCode("ORD-123456")
                .status("CONFIRMED")
                .totalAmount(new BigDecimal("500000"))
                .currency("VND")
                .shippingAddress("Hà Nội")
                .build();
        ApiResponse<InternalOrderDto> apiResponse = ApiResponse.success(orderDto);

        mockServer.expect(MockRestRequestMatchers.requestTo("http://order-service:8084/internal/orders/" + orderId + "/notification-snapshot"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header(SecurityConstants.HEADER_CALLING_SERVICE, "notification-service"))
                .andExpect(MockRestRequestMatchers.header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "order-secret-key"))
                .andRespond(MockRestResponseCreators.withSuccess(objectMapper.writeValueAsString(apiResponse), MediaType.APPLICATION_JSON));

        InternalOrderDto result = internalClientService.getOrder(orderId);

        assertNotNull(result);
        assertEquals(orderId, result.getOrderId());
        assertEquals("ORD-123456", result.getOrderCode());
        mockServer.verify();
    }

    @Test
    @DisplayName("Negative: 500 error from upstream service throws RuntimeException")
    void testGetOrder_UpstreamError_ThrowsRuntimeException() {
        UUID orderId = UUID.randomUUID();

        mockServer.expect(MockRestRequestMatchers.requestTo("http://order-service:8084/internal/orders/" + orderId + "/notification-snapshot"))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(RuntimeException.class, () -> internalClientService.getOrder(orderId));
        mockServer.verify();
    }
}
