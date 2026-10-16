package com.ecommerce.common.exception;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.response.ErrorDetail;

import java.util.List;

public class ValidationException extends BusinessException {
    public ValidationException(String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
    }

    public ValidationException(String message, List<ErrorDetail> details) {
        super(ErrorCode.VALIDATION_ERROR, message, details);
    }
}
