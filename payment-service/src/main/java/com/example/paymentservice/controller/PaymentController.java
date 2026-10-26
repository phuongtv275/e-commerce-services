package com.example.paymentservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.paymentservice.dto.PaymentInitiateRequest;
import com.example.paymentservice.dto.PaymentInitiateResponse;
import com.example.paymentservice.dto.PaymentResponse;
import com.example.paymentservice.provider.vnpay.VnPayIpnResponse;
import com.example.paymentservice.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    public static final String HEADER_TRUSTED_CLIENT_IP = "X-Trusted-Client-IP";

    private final PaymentService paymentService;

    @PostMapping("/initiate")
    public ApiResponse<PaymentInitiateResponse> initiatePayment(
            @Valid @RequestBody PaymentInitiateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        UUID customerId = UUID.fromString(authentication.getName());
        String clientIp = extractClientIp(servletRequest);
        PaymentInitiateResponse response = paymentService.initiatePayment(request, customerId, clientIp);
        return ApiResponse.success(response);
    }

    @GetMapping("/vnpay/ipn")
    public ResponseEntity<VnPayIpnResponse> handleIpnCallback(@RequestParam Map<String, String> queryParams) {
        VnPayIpnResponse response = paymentService.handleIpnCallback(queryParams);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{paymentId}")
    public ApiResponse<PaymentResponse> getPayment(
            @PathVariable UUID paymentId,
            Authentication authentication
    ) {
        UUID callerId = UUID.fromString(authentication.getName());
        boolean isPrivileged = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_FINANCE_STAFF".equals(a.getAuthority())
                        || "ROLE_ADMINISTRATOR".equals(a.getAuthority()));

        PaymentResponse response = paymentService.getPaymentById(paymentId, callerId, isPrivileged);
        return ApiResponse.success(response);
    }

    /**
     * Extract client IP using only the gateway-sanitized X-Trusted-Client-IP header
     * or servlet request remoteAddr if absent. Never trust client-supplied X-Forwarded-For directly.
     */
    private String extractClientIp(HttpServletRequest request) {
        String trustedIp = request.getHeader(HEADER_TRUSTED_CLIENT_IP);
        if (StringUtils.hasText(trustedIp)) {
            return trustedIp.trim();
        }
        return request.getRemoteAddr();
    }
}
