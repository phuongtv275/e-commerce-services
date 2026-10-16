package com.example.identityservice.controllers;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.response.ApiResponse;
import com.example.identityservice.models.dto.req.AddressReq;
import com.example.identityservice.models.dto.res.AddressRes;
import com.example.identityservice.models.services.AddressService;
import com.example.identityservice.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping({"/api/v1/users/me/addresses", "/api/v1/users/addresses"})
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AddressRes>>> getAddresses() {
        UUID userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "User must be authenticated");
        }
        List<AddressRes> list = addressService.getUserAddresses(userId);
        return ResponseEntity.ok(ApiResponse.success("Get addresses successfully", list));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AddressRes>> createAddress(@RequestBody @Valid AddressReq req) {
        UUID userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "User must be authenticated");
        }
        AddressRes created = addressService.createAddress(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Create address successfully", created));
    }

    @GetMapping("/{addressId}")
    public ResponseEntity<ApiResponse<AddressRes>> getAddress(@PathVariable UUID addressId) {
        UUID userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "User must be authenticated");
        }
        AddressRes address = addressService.getAddressById(userId, addressId);
        return ResponseEntity.ok(ApiResponse.success("Get address successfully", address));
    }

    @PutMapping("/{addressId}")
    public ResponseEntity<ApiResponse<AddressRes>> updateAddress(
            @PathVariable UUID addressId,
            @RequestBody @Valid AddressReq req) {
        UUID userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "User must be authenticated");
        }
        AddressRes updated = addressService.updateAddress(userId, addressId, req);
        return ResponseEntity.ok(ApiResponse.success("Update address successfully", updated));
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<ApiResponse<Void>> deleteAddress(@PathVariable UUID addressId) {
        UUID userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "User must be authenticated");
        }
        addressService.deleteAddress(userId, addressId);
        return ResponseEntity.ok(ApiResponse.success("Delete address successfully", null));
    }

    @PatchMapping("/{addressId}/default")
    public ResponseEntity<ApiResponse<AddressRes>> setDefaultAddress(@PathVariable UUID addressId) {
        UUID userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "User must be authenticated");
        }
        AddressRes res = addressService.setDefaultAddress(userId, addressId);
        return ResponseEntity.ok(ApiResponse.success("Set default address successfully", res));
    }
}
