package com.example.paymentservice.refund.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.paymentservice.refund.dto.RefundRequest;
import com.example.paymentservice.refund.dto.RefundResponse;
import com.example.paymentservice.refund.dto.ResolveRefundRequest;
import com.example.paymentservice.refund.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/payments")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    @PostMapping("/{paymentId}/refunds")
    @PreAuthorize("hasAnyRole('FINANCE_STAFF', 'ADMINISTRATOR') or hasAnyAuthority('ROLE_FINANCE_STAFF', 'ROLE_ADMINISTRATOR')")
    public ApiResponse<RefundResponse> createRefund(
            @PathVariable UUID paymentId,
            @Valid @RequestBody RefundRequest request,
            Authentication authentication
    ) {
        log.info("Admin refund requested for paymentId={} by actor={}", paymentId, authentication != null ? authentication.getName() : "anonymous");
        UUID actorId = extractActorId(authentication);
        RefundResponse response = refundService.createManualRefund(paymentId, request, actorId);
        return ApiResponse.success("Yêu cầu hoàn tiền đã được tiếp nhận", response);
    }

    @PostMapping("/{paymentId}/refunds/{refundId}/resolve")
    @PreAuthorize("hasAnyRole('FINANCE_STAFF', 'ADMINISTRATOR') or hasAnyAuthority('ROLE_FINANCE_STAFF', 'ROLE_ADMINISTRATOR')")
    public ApiResponse<RefundResponse> resolveRefund(
            @PathVariable UUID paymentId,
            @PathVariable UUID refundId,
            @Valid @RequestBody ResolveRefundRequest request,
            Authentication authentication
    ) {
        String actor = authentication != null ? authentication.getName() : "ADMIN_USER";
        log.info("Admin manual resolution for paymentId={}, refundId={} by actor={}", paymentId, refundId, actor);
        RefundResponse response = refundService.resolveRefund(paymentId, refundId, request, actor);
        return ApiResponse.success("Xử lý đối soát hoàn tiền thành công", response);
    }

    private UUID extractActorId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
