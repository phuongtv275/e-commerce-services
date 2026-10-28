package com.example.notificationservice.service;

import com.ecommerce.common.constant.SecurityConstants;
import com.ecommerce.common.response.ApiResponse;
import com.example.notificationservice.dto.internal.InternalOrderDto;
import com.example.notificationservice.dto.internal.InternalUserDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
public class InternalClientService {

    private record CachedUser(InternalUserDto user, Instant expiryTime) {}
    private final ConcurrentMap<UUID, CachedUser> userCache = new ConcurrentHashMap<>();
    private static final Duration USER_CACHE_TTL = Duration.ofMinutes(5);

    private final RestTemplate restTemplate;
    private final String identityServiceUrl;
    private final String identityInternalKey;
    private final String orderServiceUrl;
    private final String orderInternalKey;

    public InternalClientService(
            RestTemplateBuilder builder,
            @Value("${services.identity-service.base-url:http://localhost:8081}") String identityServiceUrl,
            @Value("${services.identity-service.internal-key:}") String identityInternalKey,
            @Value("${services.identity-service.connect-timeout-ms:3000}") long identityConnectTimeout,
            @Value("${services.identity-service.read-timeout-ms:5000}") long identityReadTimeout,
            @Value("${services.order-service.base-url:http://localhost:8084}") String orderServiceUrl,
            @Value("${services.order-service.internal-key:}") String orderInternalKey,
            @Value("${services.order-service.connect-timeout-ms:3000}") long orderConnectTimeout,
            @Value("${services.order-service.read-timeout-ms:5000}") long orderReadTimeout
    ) {
        this.identityServiceUrl = identityServiceUrl;
        this.identityInternalKey = identityInternalKey;
        this.orderServiceUrl = orderServiceUrl;
        this.orderInternalKey = orderInternalKey;

        long connectTimeout = Math.max(identityConnectTimeout, orderConnectTimeout);
        long readTimeout = Math.max(identityReadTimeout, orderReadTimeout);

        this.restTemplate = builder
                .connectTimeout(Duration.ofMillis(connectTimeout > 0 ? connectTimeout : 3000))
                .readTimeout(Duration.ofMillis(readTimeout > 0 ? readTimeout : 5000))
                .build();
    }

    private HttpHeaders buildHeaders(String internalKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SecurityConstants.HEADER_CALLING_SERVICE, "notification-service");
        if (internalKey != null && !internalKey.isBlank()) {
            headers.set(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, internalKey);
        }
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    public InternalUserDto getUser(UUID userId) {
        CachedUser cached = userCache.get(userId);
        if (cached != null && cached.expiryTime().isAfter(Instant.now())) {
            return cached.user();
        }

        String url = identityServiceUrl + "/internal/users/" + userId;
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(identityInternalKey));
            ResponseEntity<ApiResponse<InternalUserDto>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                InternalUserDto result = response.getBody().getData();
                if (result != null) {
                    userCache.put(userId, new CachedUser(result, Instant.now().plus(USER_CACHE_TTL)));
                }
                return result;
            }
            throw new RuntimeException("Failed to retrieve user: status " + response.getStatusCode());
        } catch (Exception e) {
            log.error("Internal identity lookup failed for userId: {}", userId);
            throw new RuntimeException("Internal lookup failure for userId " + userId + ": " + e.getMessage(), e);
        }
    }

    public InternalOrderDto getOrder(UUID orderId) {
        String url = orderServiceUrl + "/internal/orders/" + orderId + "/notification-snapshot";
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(orderInternalKey));
            ResponseEntity<ApiResponse<InternalOrderDto>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody().getData();
            }
            throw new RuntimeException("Failed to retrieve order notification snapshot: status " + response.getStatusCode());
        } catch (Exception e) {
            log.error("Internal order snapshot lookup failed for orderId: {}", orderId);
            throw new RuntimeException("Internal lookup failure for orderId " + orderId + ": " + e.getMessage(), e);
        }
    }
}
