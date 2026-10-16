package com.example.apigateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * GlobalFilter đảm bảo mọi request và response đi qua API Gateway đều có header X-Correlation-Id:
 * - Bảo lưu header X-Correlation-Id nếu client gửi lên.
 * - Tự động tạo UUID ngẫu nhiên nếu client chưa gửi.
 * - Gán header X-Correlation-Id vào cả request (chuyển tiếp downstream) và response (trả về cho client).
 */
@Component
public class CorrelationIdGlobalFilter implements WebFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1; // Chạy ngay sau Sanitization (-2)
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String incomingCorrId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        String correlationId = (incomingCorrId != null && !incomingCorrId.isBlank())
                ? incomingCorrId.trim()
                : UUID.randomUUID().toString();

        // Gán correlationId vào response để client theo dõi
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);

        // Gán correlationId vào request để chuyển tiếp downstream
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }
}
