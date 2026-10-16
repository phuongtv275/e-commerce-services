package com.example.apigateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Cấu hình KeyResolvers cho thuật toán Token Bucket (Redis Rate Limiting).
 *
 * Phân chia hai cấp độ:
 * 1. ipKeyResolver: Giới hạn theo địa chỉ IP thực của client cho Public Endpoints (auth, catalog,...).
 * 2. userKeyResolver: Giới hạn theo User ID (X-User-Id) cho Protected Endpoints (checkout, cart, profile,...).
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Resolver dựa trên địa chỉ IP của client.
     * Đọc ưu tiên từ header X-Forwarded-For nếu đi qua reverse proxy/load balancer,
     * hoặc fallback về RemoteAddress.
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            if (Boolean.TRUE.equals(exchange.getAttribute("RATE_LIMITER_BYPASS"))) {
                return Mono.just("bypass:" + java.util.UUID.randomUUID());
            }
            String clientIp = Optional.ofNullable(exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"))
                    .map(ips -> ips.split(",")[0].trim())
                    .orElseGet(() -> Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                            .map(addr -> addr.getAddress().getHostAddress())
                            .orElse("unknown-ip"));
            return Mono.just(clientIp);
        };
    }

    /**
     * Resolver dựa trên định danh người dùng X-User-Id đã được Gateway xác thực.
     * Fallback về IP nếu chưa có X-User-Id.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            if (Boolean.TRUE.equals(exchange.getAttribute("RATE_LIMITER_BYPASS"))) {
                return Mono.just("bypass:" + java.util.UUID.randomUUID());
            }
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (StringUtils.hasText(userId)) {
                return Mono.just("user:" + userId.trim());
            }
            return ipKeyResolver().resolve(exchange);
        };
    }
}
