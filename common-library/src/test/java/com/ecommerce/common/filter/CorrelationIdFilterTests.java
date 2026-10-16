package com.ecommerce.common.filter;

import com.ecommerce.common.constant.SecurityConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdFilterTests {

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Positive: Giữ nguyên Correlation ID khi request đã có sẵn header")
    void testExistingCorrelationId_Positive() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String incomingId = "custom-trace-id-999";
        request.addHeader(SecurityConstants.HEADER_CORRELATION_ID, incomingId);

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInsideChain = new AtomicReference<>();

        FilterChain filterChain = (req, res) -> {
            mdcInsideChain.set(MDC.get(SecurityConstants.MDC_CORRELATION_ID));
        };

        filter.doFilter(request, response, filterChain);

        assertEquals(incomingId, mdcInsideChain.get());
        assertEquals(incomingId, response.getHeader(SecurityConstants.HEADER_CORRELATION_ID));
        assertNull(MDC.get(SecurityConstants.MDC_CORRELATION_ID)); // Đảm bảo MDC được dọn dẹp sau request
    }

    @Test
    @DisplayName("Negative: Tự sinh UUID mới khi request không có header X-Correlation-Id")
    void testMissingCorrelationId_GeneratesNewUuid_Negative() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInsideChain = new AtomicReference<>();

        FilterChain filterChain = (req, res) -> {
            mdcInsideChain.set(MDC.get(SecurityConstants.MDC_CORRELATION_ID));
        };

        filter.doFilter(request, response, filterChain);

        String generatedId = mdcInsideChain.get();
        assertNotNull(generatedId);
        assertDoesNotThrow(() -> UUID.fromString(generatedId)); // Phải là UUID hợp lệ
        assertEquals(generatedId, response.getHeader(SecurityConstants.HEADER_CORRELATION_ID));
        assertNull(MDC.get(SecurityConstants.MDC_CORRELATION_ID));
    }
}
