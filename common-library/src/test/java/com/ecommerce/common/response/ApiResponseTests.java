package com.ecommerce.common.response;

import com.ecommerce.common.constant.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTests {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        MDC.put("correlationId", "test-corr-id-123");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Positive: Factory success tạo response với code SUCCESS, correlationId từ MDC và payload data")
    void testSuccessFactory_Positive() throws Exception {
        String data = "Hello World";
        ApiResponse<String> response = ApiResponse.success(data);

        assertNotNull(response);
        assertEquals("SUCCESS", response.getCode());
        assertEquals("Thao tác thành công", response.getMessage());
        assertEquals("Hello World", response.getData());
        assertEquals("test-corr-id-123", response.getCorrelationId());
        assertNotNull(response.getTimestamp());
        assertNull(response.getDetails());

        String json = objectMapper.writeValueAsString(response);
        assertTrue(json.contains("\"code\":\"SUCCESS\""));
        assertTrue(json.contains("\"data\":\"Hello World\""));
        assertTrue(json.contains("\"correlationId\":\"test-corr-id-123\""));
    }

    @Test
    @DisplayName("Negative: Factory error tạo response với mã lỗi và danh sách details validation chi tiết")
    void testErrorFactoryWithDetails_Negative() throws Exception {
        List<ErrorDetail> details = List.of(
                ErrorDetail.builder().field("email").rejectedValue("invalid-email").message("Email không đúng định dạng").build(),
                ErrorDetail.builder().field("password").rejectedValue("123").message("Mật khẩu quá ngắn").build()
        );

        ApiResponse<Void> response = ApiResponse.error(ErrorCode.VALIDATION_ERROR, "Dữ liệu không hợp lệ", details);

        assertNotNull(response);
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), response.getCode());
        assertEquals("Dữ liệu không hợp lệ", response.getMessage());
        assertNull(response.getData());
        assertEquals("test-corr-id-123", response.getCorrelationId());
        assertEquals(2, response.getDetails().size());
        assertEquals("email", response.getDetails().get(0).getField());

        String json = objectMapper.writeValueAsString(response);
        assertTrue(json.contains("\"code\":\"VALIDATION_ERROR\""));
        assertTrue(json.contains("\"field\":\"email\""));
        assertFalse(json.contains("\"data\"")); // JsonInclude.Include.NON_NULL
    }
}
