package com.example.apigateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * GlobalFilter chạy ở mức ưu tiên cao nhất (HIGHEST_PRECEDENCE) trên 100% request
 * nhằm loại bỏ toàn bộ các header định danh người dùng và token assertion do client
 * bên ngoài tự gửi lên, ngăn chặn tuyệt đối nguy cơ Header Injection / Identity Spoofing.
 */
@Component
public class SanitizationGlobalFilter implements WebFilter, Ordered {

    private static final List<String> SENSITIVE_HEADERS = List.of(
            "X-User-Id",
            "X-User-Role",
            "X-User-Roles",
            "X-User-Email",
            "X-Gateway-Assertion",
            "X-Internal-Service-Key",
            "X-Calling-Service"
    );

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(httpHeaders -> SENSITIVE_HEADERS.forEach(httpHeaders::remove))
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }
}
