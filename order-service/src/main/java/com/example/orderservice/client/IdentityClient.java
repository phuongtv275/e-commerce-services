package com.example.orderservice.client;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.response.ApiResponse;
import com.example.orderservice.client.dto.IdentityAddressResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
public class IdentityClient {

    private final RestClient restClient;
    private final String internalKey;

    public IdentityClient(
            @Value("${services.identity-service.base-url:http://localhost:8081}") String baseUrl,
            @Value("${services.identity-service.internal-key:}") String internalKey,
            @Value("${services.identity-service.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${services.identity-service.read-timeout-ms:5000}") int readTimeoutMs) {

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
            return "http://localhost:8081";
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "http://" + trimmed;
        }
        return trimmed;
    }

    public IdentityAddressResponse getAddress(UUID userId, UUID addressId) {
        String correlationId = MDC.get(SecurityConstants.MDC_CORRELATION_ID);

        try {
            ApiResponse<IdentityAddressResponse> response = restClient.get()
                    .uri("/internal/users/{userId}/addresses/{addressId}", userId, addressId)
                    .header(SecurityConstants.HEADER_CALLING_SERVICE, "order-service")
                    .header(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, internalKey)
                    .headers(headers -> {
                        if (correlationId != null) {
                            headers.set(SecurityConstants.HEADER_CORRELATION_ID, correlationId);
                        }
                    })
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        if (resp.getStatusCode().value() == 404) {
                            throw new ResourceNotFoundException("Địa chỉ không tồn tại hoặc không thuộc người dùng");
                        }
                        throw new BusinessException(ErrorCode.ACCESS_DENIED, "Không có quyền truy cập địa chỉ");
                    })
                    .body(new ParameterizedTypeReference<ApiResponse<IdentityAddressResponse>>() {});

            if (response == null || !ErrorCode.SUCCESS.name().equals(response.getCode()) || response.getData() == null) {
                log.error("Failed to fetch address from identity-service: {}", response != null ? response.getMessage() : "null");
                throw new ResourceNotFoundException("Không tìm thấy thông tin địa chỉ giao hàng");
            }

            IdentityAddressResponse address = response.getData();
            if (address.getUserId() != null && !userId.equals(address.getUserId())) {
                log.warn("Address userId mismatch: requested={}, received={}", userId, address.getUserId());
                throw new ResourceNotFoundException("Địa chỉ không thuộc người dùng hiện tại");
            }

            return address;

        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (RestClientResponseException e) {
            log.error("Identity service error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 404) {
                throw new ResourceNotFoundException("Địa chỉ giao hàng không tồn tại");
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Lỗi kết nối Identity Service: " + e.getStatusCode());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error communicating with identity-service", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Không thể kết nối Identity Service: " + e.getMessage());
        }
    }
}
