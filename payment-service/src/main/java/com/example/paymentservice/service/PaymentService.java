package com.example.paymentservice.service;

import com.example.paymentservice.dto.PaymentInitiateRequest;
import com.example.paymentservice.dto.PaymentInitiateResponse;
import com.example.paymentservice.dto.PaymentResponse;
import com.example.paymentservice.provider.vnpay.VnPayIpnResponse;

import java.util.Map;
import java.util.UUID;

public interface PaymentService {

    PaymentInitiateResponse initiatePayment(PaymentInitiateRequest request, UUID customerId, String clientIp);

    VnPayIpnResponse handleIpnCallback(Map<String, String> queryParams);

    PaymentResponse getPaymentById(UUID paymentId, UUID callerId, boolean isPrivilegedStaff);
}
