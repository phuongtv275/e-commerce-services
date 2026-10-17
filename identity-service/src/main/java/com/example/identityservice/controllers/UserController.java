package com.example.identityservice.controllers;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.response.ApiResponse;
import com.example.identityservice.models.dto.req.UpdateProfileReq;
import com.example.identityservice.models.dto.res.UserProfileRes;
import com.example.identityservice.models.services.UserService;
import com.example.identityservice.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping({"/me", "/profile"})
    public ResponseEntity<ApiResponse<UserProfileRes>> getProfile() {
        UUID userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "User must be authenticated");
        }
        UserProfileRes profile = userService.getProfile(userId);
        return ResponseEntity.ok(ApiResponse.success("Get profile successfully", profile));
    }

    @RequestMapping(value = {"/me", "/profile"}, method = {RequestMethod.PATCH, RequestMethod.PUT})
    public ResponseEntity<ApiResponse<UserProfileRes>> updateProfile(@RequestBody @Valid UpdateProfileReq req) {
        UUID userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "User must be authenticated");
        }
        UserProfileRes updated = userService.updateProfile(userId, req);
        return ResponseEntity.ok(ApiResponse.success("Update profile successfully", updated));
    }
}
