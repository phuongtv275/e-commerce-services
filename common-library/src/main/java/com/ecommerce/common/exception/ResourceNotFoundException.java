package com.ecommerce.common.exception;

import com.ecommerce.common.constant.ErrorCode;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    public ResourceNotFoundException(String resourceName, Object identifier) {
        super(ErrorCode.RESOURCE_NOT_FOUND, String.format("Không tìm thấy %s với mã: %s", resourceName, identifier));
    }
}
