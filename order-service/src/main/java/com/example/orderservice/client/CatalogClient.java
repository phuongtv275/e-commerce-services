package com.example.orderservice.client;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.response.ApiResponse;
import com.example.orderservice.client.dto.CatalogBatchSkuRequest;
import com.example.orderservice.client.dto.CatalogBatchSkuResponse;
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
public class CatalogClient {

    private final RestClient restClient;
    private final String internalKey;

    public CatalogClient(
            @Value("${services.catalog-service.base-url:http://localhost:8082}") String baseUrl,
            @Value("${services.catalog-service.internal-key:}") String internalKey,
            @Value("${services.catalog-service.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${services.catalog-service.read-timeout-ms:5000}") int readTimeoutMs) {

        this.internalKey = internalKey;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public CatalogBatchSkuResponse getBatchSkus(List<UUID> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return new CatalogBatchSkuResponse();
        }

        String correlationId = MDC.get(SecurityConstants.MDC_CORRELATION_ID);

        try {
            ApiResponse<CatalogBatchSkuResponse> response = restClient.post()
                    .uri("/internal/catalog/skus/batch")
                    .header(SecurityConstants.HEADER_CALLING_SERVICE, "order-service")
                    .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, internalKey)
                    .headers(headers -> {
                        if (correlationId != null) {
                            headers.set(SecurityConstants.HEADER_CORRELATION_ID, correlationId);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CatalogBatchSkuRequest(skuIds))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<CatalogBatchSkuResponse>>() {});

            if (response == null || !ErrorCode.SUCCESS.name().equals(response.getCode()) || response.getData() == null) {
                log.error("Failed to fetch batch SKUs from catalog-service: {}", response != null ? response.getMessage() : "null");
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Không thể lấy thông tin sản phẩm từ Catalog");
            }

            return response.getData();

        } catch (RestClientResponseException e) {
            log.error("Catalog service responded with status {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Lỗi kết nối Catalog Service: " + e.getStatusCode());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error communicating with catalog-service", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Không thể kết nối Catalog Service: " + e.getMessage());
        }
    }
}
