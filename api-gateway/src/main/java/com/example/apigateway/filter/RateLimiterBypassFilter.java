package com.example.apigateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Filter hỗ trợ kiểm thử tải cao (Load Testing) trên các route đọc catalog công khai.
 *
 * Ràng buộc an ninh nghiêm ngặt:
 * 1. Profile Isolation: Chỉ kích hoạt trên profile load-test hoặc staging; bị vô hiệu hóa ở production.
 * 2. Route Whitelist Enforcement: CHỈ cho phép bypass trên các API GET công khai của Catalog.
 * 3. Secret Verification: Khớp với header X-Load-Test-Secret.
 * 4. Header Stripping: Tự động xóa bỏ X-Load-Test-Secret trước khi forward downstream để tránh rò rỉ.
 */
@Slf4j
@Component
@Profile({"load-test", "staging"})
public class RateLimiterBypassFilter implements WebFilter, Ordered {

    public static final String LOAD_TEST_HEADER = "X-Load-Test-Secret";
    public static final String BYPASS_ATTRIBUTE = "RATE_LIMITER_BYPASS";

    private static final List<String> ALLOWED_BYPASS_PATTERNS = List.of(
            "/api/v1/catalog/products/**",
            "/api/v1/catalog/categories/**",
            "/api/v1/catalog/brands/**",
            "/api/v1/catalog/search/**"
    );

    private final String loadTestSecret;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public RateLimiterBypassFilter(@Value("${load-test.secret:${LOAD_TEST_SECRET:}}") String loadTestSecret) {
        this.loadTestSecret = loadTestSecret;
        if (!StringUtils.hasText(this.loadTestSecret)) {
            log.warn("RateLimiterBypassFilter initialized without LOAD_TEST_SECRET. Bypass will be rejected.");
        }
    }

    @Override
    public int getOrder() {
        return 0; // Chạy sau Sanitization (-2) & Correlation (-1)
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        String secretHeader = request.getHeaders().getFirst(LOAD_TEST_HEADER);

        boolean isAllowedMethod = HttpMethod.GET.equals(request.getMethod());
        boolean isWhitelistedRoute = isAllowedMethod && ALLOWED_BYPASS_PATTERNS.stream()
                .anyMatch(pattern -> antPathMatcher.match(pattern, path));

        boolean isValidSecret = StringUtils.hasText(loadTestSecret) &&
                StringUtils.hasText(secretHeader) &&
                loadTestSecret.equals(secretHeader.trim());

        if (isWhitelistedRoute && isValidSecret) {
            log.info("Rate limiter bypass authorized for path: {}", path);
            // Đánh dấu thuộc tính bypass trên exchange
            exchange.getAttributes().put(BYPASS_ATTRIBUTE, true);

            // Bắt buộc xóa secret header khỏi request trước khi chuyển tiếp downstream
            ServerHttpRequest sanitizedRequest = request.mutate()
                    .headers(httpHeaders -> httpHeaders.remove(LOAD_TEST_HEADER))
                    .build();

            return chain.filter(exchange.mutate().request(sanitizedRequest).build());
        }

        // Nếu gửi secret nhưng không hợp lệ hoặc route không được phép, đảm bảo không bypass
        exchange.getAttributes().put(BYPASS_ATTRIBUTE, false);
        return chain.filter(exchange);
    }
}
