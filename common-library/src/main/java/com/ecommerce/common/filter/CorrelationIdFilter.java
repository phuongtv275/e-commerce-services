package com.ecommerce.common.filter;

import com.ecommerce.common.constant.SecurityConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(SecurityConstants.HEADER_CORRELATION_ID);

        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        try {
            MDC.put(SecurityConstants.MDC_CORRELATION_ID, correlationId);
            response.setHeader(SecurityConstants.HEADER_CORRELATION_ID, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(SecurityConstants.MDC_CORRELATION_ID);
        }
    }
}
