package com.example.paymentservice.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.response.ApiResponse;
import com.example.paymentservice.config.OrderServiceProperties;
import com.example.paymentservice.dto.OrderInternalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderClientImpl implements OrderClient {

    private final RestClient restClient;
    private final OrderServiceProperties orderServiceProperties;
    private final ObjectMapper objectMapper;

    @Override
    public OrderInternalResponse getOrder(UUID orderId) {
        String baseUrl = normalizeBaseUrl(orderServiceProperties.getBaseUrl());
        String url = baseUrl + "/internal/orders/" + orderId;
        log.info("Fetching order {} from {}", orderId, url);

        try {
            ApiResponse<OrderInternalResponse> response = restClient.get()
                    .uri(url)
                    .header(SecurityConstants.HEADER_CALLING_SERVICE, "payment-service")
                    .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, orderServiceProperties.getInternalKey())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        if (resp.getStatusCode().value() == 404) {
                            throw new ResourceNotFoundException("Order not found with ID: " + orderId);
                        }
                        throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "Order service client error: " + resp.getStatusCode());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "Order service server error: " + resp.getStatusCode());
                    })
                    .body(new ParameterizedTypeReference<ApiResponse<OrderInternalResponse>>() {});

            if (response != null && response.getData() != null) {
                return response.getData();
            }

            // Fallback: If body was directly an OrderInternalResponse (not wrapped in ApiResponse)
            return restClient.get()
                    .uri(url)
                    .header(SecurityConstants.HEADER_CALLING_SERVICE, "payment-service")
                    .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, orderServiceProperties.getInternalKey())
                    .retrieve()
                    .body(OrderInternalResponse.class);
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to call internal order-service for order {}: {}", orderId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "Unable to fetch order information: " + e.getMessage());
        }
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:8084";
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "http://" + trimmed;
        }
        return trimmed;
    }
}
