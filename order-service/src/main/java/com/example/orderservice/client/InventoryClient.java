package com.example.orderservice.client;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.response.ApiResponse;
import com.example.orderservice.client.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class InventoryClient {

    private final RestClient restClient;
    private final String internalKey;

    public InventoryClient(
            @Value("${services.inventory-service.base-url:http://localhost:8083}") String baseUrl,
            @Value("${services.inventory-service.internal-key:}") String internalKey,
            @Value("${services.inventory-service.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${services.inventory-service.read-timeout-ms:5000}") int readTimeoutMs) {

        this.internalKey = internalKey;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .requestFactory(requestFactory)
                .build();
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:8083";
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "http://" + trimmed;
        }
        return trimmed;
    }

    public InventoryStockBatchResponse getBatchStocks(List<UUID> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return new InventoryStockBatchResponse();
        }

        String correlationId = MDC.get(SecurityConstants.MDC_CORRELATION_ID);

        try {
            ApiResponse<InventoryStockBatchResponse> response = restClient.post()
                    .uri("/internal/inventory/stocks/batch")
                    .header(SecurityConstants.HEADER_CALLING_SERVICE, "order-service")
                    .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, internalKey)
                    .headers(headers -> {
                        if (correlationId != null) {
                            headers.set(SecurityConstants.HEADER_CORRELATION_ID, correlationId);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new InventoryStockBatchRequest(skuIds))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<InventoryStockBatchResponse>>() {});

            if (response == null || !ErrorCode.SUCCESS.name().equals(response.getCode()) || response.getData() == null) {
                log.error("Failed to query stock batch: {}", response != null ? response.getMessage() : "null");
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Không thể kiểm tra tồn kho");
            }

            return response.getData();

        } catch (RestClientResponseException e) {
            log.error("Inventory stock batch responded with status {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Lỗi kết nối Inventory Service: " + e.getStatusCode());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error communicating with inventory-service stocks batch", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Không thể kết nối Inventory Service: " + e.getMessage());
        }
    }

    public InventoryReservationResponse reserve(UUID orderId, List<InventoryReservationItemRequest> items) {
        String correlationId = MDC.get(SecurityConstants.MDC_CORRELATION_ID);

        try {
            ApiResponse<InventoryReservationResponse> response = restClient.post()
                    .uri("/internal/inventory/reservations")
                    .header(SecurityConstants.HEADER_CALLING_SERVICE, "order-service")
                    .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, internalKey)
                    .headers(headers -> {
                        if (correlationId != null) {
                            headers.set(SecurityConstants.HEADER_CORRELATION_ID, correlationId);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new InventoryReservationRequest(orderId, items))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<InventoryReservationResponse>>() {});

            if (response == null || !ErrorCode.SUCCESS.name().equals(response.getCode()) || response.getData() == null) {
                log.error("Reservation failed: {}", response != null ? response.getMessage() : "null");
                throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT, "Không thể giữ hàng tồn kho");
            }

            return response.getData();

        } catch (RestClientResponseException e) {
            log.error("Inventory reservation failed with status {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 409) {
                throw new ConflictException(ErrorCode.INVENTORY_INSUFFICIENT, "Số lượng tồn kho không đủ để đặt hàng");
            }
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT, "Không thể giữ tồn kho: " + e.getStatusCode());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calling inventory-service reservation", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Lỗi kết nối khi giữ tồn kho: " + e.getMessage());
        }
    }

    public void release(UUID orderId, String reason) {
        String correlationId = MDC.get(SecurityConstants.MDC_CORRELATION_ID);

        try {
            restClient.post()
                    .uri("/internal/inventory/reservations/{orderId}/release", orderId)
                    .header(SecurityConstants.HEADER_CALLING_SERVICE, "order-service")
                    .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, internalKey)
                    .headers(headers -> {
                        if (correlationId != null) {
                            headers.set(SecurityConstants.HEADER_CORRELATION_ID, correlationId);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new InventoryReleaseRequest(reason))
                    .retrieve()
                    .toBodilessEntity();

            log.info("Successfully released inventory reservation for orderId: {}", orderId);

        } catch (Exception e) {
            log.error("Failed to release inventory reservation for orderId {}: {}", orderId, e.getMessage());
            // Idempotent release, do not re-throw to allow compensation/rollback completion
        }
    }
}
