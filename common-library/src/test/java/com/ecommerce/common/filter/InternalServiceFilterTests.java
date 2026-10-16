package com.ecommerce.common.filter;

import com.ecommerce.common.constant.SecurityConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InternalServiceFilterTests {

    private InternalServiceFilter filter;

    @BeforeEach
    void setUp() {
        Map<String, String> internalKeys = Map.of(
                "order-service", "secret-order-key-123",
                "payment-service", "secret-payment-key-456"
        );
        filter = new InternalServiceFilter(internalKeys);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Positive: Header caller và key hợp lệ cấp đúng scoped role ROLE_SERVICE_<CALLER>")
    void testValidInternalCall_Positive() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/inventory/reservations");
        request.addHeader(SecurityConstants.HEADER_CALLING_SERVICE, "order-service");
        request.addHeader(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "secret-order-key-123");

        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(auth);
            assertEquals("order-service", auth.getPrincipal());
            assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SERVICE_ORDER")));
        };

        filter.doFilter(request, response, filterChain);
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("Negative: Khóa dịch vụ nội bộ sai bị từ chối với HTTP 403")
    void testInvalidInternalKey_Negative() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/inventory/reservations");
        request.addHeader(SecurityConstants.HEADER_CALLING_SERVICE, "order-service");
        request.addHeader(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "wrong-key");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = (req, res) -> fail("Filter chain không được phép chạy khi sai key nội bộ");

        filter.doFilter(request, response, filterChain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("ACCESS_DENIED"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Negative: Thiếu header xác thực nội bộ bị từ chối với HTTP 401")
    void testMissingHeaders_Negative() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/inventory/reservations");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = (req, res) -> fail("Filter chain không được phép chạy khi thiếu header nội bộ");

        filter.doFilter(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("Negative: Chặn việc truyền X-Gateway-Assertion vào endpoint /internal/** với HTTP 403")
    void testGatewayAssertionOnInternalEndpoint_Negative() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/users/123");
        request.addHeader(SecurityConstants.HEADER_GATEWAY_ASSERTION, "some-fake-jwt-assertion");
        request.addHeader(SecurityConstants.HEADER_CALLING_SERVICE, "order-service");
        request.addHeader(SecurityConstants.HEADER_INTERNAL_SERVICE_KEY, "secret-order-key-123");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = (req, res) -> fail("Filter chain không được phép chạy khi có X-Gateway-Assertion trên /internal");

        filter.doFilter(request, response, filterChain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("ACCESS_DENIED"));
        assertTrue(response.getContentAsString().contains("Không được phép sử dụng Gateway Assertion trên endpoint nội bộ"));
    }

    @Test
    @DisplayName("Positive: Bỏ qua filter đối với các endpoint công khai (không bắt đầu bằng /internal)")
    void testNonInternalEndpoint_Skipped() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean[] chainExecuted = {false};
        FilterChain filterChain = (req, res) -> chainExecuted[0] = true;

        filter.doFilter(request, response, filterChain);

        assertTrue(chainExecuted[0]);
        assertEquals(200, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
