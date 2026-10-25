package com.example.orderservice.security;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static UUID getRequiredCurrentCustomerId() {
        UUID customerId = getCurrentCustomerId();
        if (customerId == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "User must be authenticated");
        }
        return customerId;
    }

    public static UUID getCurrentCustomerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof String principalStr) {
            try {
                return UUID.fromString(principalStr);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (auth.getName() != null) {
            try {
                return UUID.fromString(auth.getName());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }
}
