package com.example.paymentservice.refund.service;

import com.example.paymentservice.entity.Refund;
import com.example.paymentservice.refund.dto.RefundRequest;
import com.example.paymentservice.refund.dto.RefundResponse;
import com.example.paymentservice.refund.dto.ResolveRefundRequest;

import java.math.BigDecimal;
import java.util.UUID;

public interface RefundService {

    RefundResponse createManualRefund(UUID paymentId, RefundRequest request, UUID actorId);

    RefundResponse resolveRefund(UUID paymentId, UUID refundId, ResolveRefundRequest request, String actor);

    Refund processAutoRefund(UUID orderId, BigDecimal amount, String reason, String correlationId);

    void reconcilePendingRefund(UUID refundId);
}
