package com.example.identityservice.controllers;

import com.ecommerce.common.response.ApiResponse;
import com.example.identityservice.models.dto.res.InternalAddressRes;
import com.example.identityservice.models.dto.res.InternalUserRes;
import com.example.identityservice.models.services.AddressService;
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
    private final AddressService addressService;

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<InternalUserRes>> getInternalUser(@PathVariable UUID userId) {
        log.info("Received internal get user request for userId: {}", userId);
        InternalUserRes res = userService.getInternalUser(userId);
        return ResponseEntity.ok(ApiResponse.success("Get internal user successfully", res));
    }

    @GetMapping("/{userId}/addresses/{addressId}")
    @PreAuthorize("hasAuthority('ROLE_SERVICE_ORDER')")
    public ResponseEntity<ApiResponse<InternalAddressRes>> getInternalUserAddress(
            @PathVariable UUID userId,
            @PathVariable UUID addressId) {
        log.info("Received internal get address request for userId: {}, addressId: {}", userId, addressId);
        InternalAddressRes res = addressService.getInternalAddress(userId, addressId);
        return ResponseEntity.ok(ApiResponse.success("Get internal user address successfully", res));
    }
}
