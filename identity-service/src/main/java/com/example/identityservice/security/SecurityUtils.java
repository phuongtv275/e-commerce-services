package com.example.identityservice.security;

import com.example.identityservice.security.principal.MyUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof MyUserDetails myUserDetails && myUserDetails.getUser() != null) {
            return myUserDetails.getUser().getId();
        }
        if (principal instanceof String principalStr) {
            try {
                return UUID.fromString(principalStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    public static String getCurrentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof MyUserDetails myUserDetails && myUserDetails.getUser() != null) {
            return myUserDetails.getUser().getEmail();
        }
        return auth.getName();
    }
}
