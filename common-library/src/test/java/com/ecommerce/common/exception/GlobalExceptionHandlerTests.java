package com.ecommerce.common.exception;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.response.ApiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTests {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        MDC.put("correlationId", "test-corr-handler");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Positive: Bắt ResourceNotFoundException trả về HTTP 404 và body tương ứng")
    void handleResourceNotFoundException_Positive() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Product", "1001");
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND.name(), response.getBody().getCode());
        assertTrue(response.getBody().getMessage().contains("Không tìm thấy Product với mã: 1001"));
        assertEquals("test-corr-handler", response.getBody().getCorrelationId());
    }

    @Test
    @DisplayName("Positive: Bắt ConflictException trả về HTTP 409")
    void handleConflictException_Positive() {
        ConflictException ex = new ConflictException("Đơn hàng đang ở trạng thái không thể hủy");
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.STATE_CONFLICT.name(), response.getBody().getCode());
        assertEquals("Đơn hàng đang ở trạng thái không thể hủy", response.getBody().getMessage());
    }

    @Test
    @DisplayName("Negative: Bắt MethodArgumentNotValidException trích xuất chi tiết field errors")
    void handleValidationException_Negative() throws NoSuchMethodException {
        TestTarget target = new TestTarget();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "testTarget");
        bindingResult.addError(new FieldError("testTarget", "email", "invalid-email", false, null, null, "Email không hợp lệ"));
        bindingResult.addError(new FieldError("testTarget", "age", "-5", false, null, null, "Tuổi phải lớn hơn 0"));

        Method method = getClass().getDeclaredMethod("dummyMethod", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidationException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), response.getBody().getCode());
        assertEquals(2, response.getBody().getDetails().size());
        assertEquals("email", response.getBody().getDetails().get(0).getField());
        assertEquals("Tuổi phải lớn hơn 0", response.getBody().getDetails().get(1).getMessage());
    }

    @Test
    @DisplayName("Negative: Bắt NullPointerException/Exception chung trả về HTTP 500 và che giấu stack trace")
    void handleGeneralException_Negative() {
        NullPointerException ex = new NullPointerException("Null pointer reference in database query");
        ResponseEntity<ApiResponse<Void>> response = handler.handleGeneralException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.INTERNAL_ERROR.name(), response.getBody().getCode());
        // Không để lộ thông điệp kỹ thuật hoặc stack trace
        assertEquals("Đã xảy ra lỗi nội bộ hệ thống. Vui lòng thử lại sau.", response.getBody().getMessage());
        assertEquals("test-corr-handler", response.getBody().getCorrelationId());
    }

    private void dummyMethod(String param) {}

    static class TestTarget {}
}
