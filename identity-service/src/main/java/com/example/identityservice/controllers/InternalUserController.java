package com.example.identityservice.controllers;

import com.ecommerce.common.response.ApiResponse;
import com.example.identityservice.models.dto.res.InternalUserRes;
import com.example.identityservice.models.services.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/internal/users")
@PreAuthorize("hasAnyAuthority('ROLE_SERVICE_ORDER', 'ROLE_SERVICE_NOTIFICATION')")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserService userService;

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<InternalUserRes>> getInternalUser(@PathVariable UUID userId) {
        log.info("Received internal get user request for userId: {}", userId);
        InternalUserRes res = userService.getInternalUser(userId);
        return ResponseEntity.ok(ApiResponse.success("Get internal user successfully", res));
    }
}
