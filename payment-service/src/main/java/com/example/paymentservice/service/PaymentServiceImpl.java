package com.example.paymentservice.service;

import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.exception.ValidationException;
import com.example.paymentservice.config.VnPayProperties;
import com.example.paymentservice.dto.OrderInternalResponse;
import com.example.paymentservice.dto.PaymentInitiateRequest;
import com.example.paymentservice.dto.PaymentInitiateResponse;
import com.example.paymentservice.dto.PaymentResponse;
import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentAuditLog;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.provider.vnpay.*;
import com.example.paymentservice.repository.PaymentAuditLogRepository;
import com.example.paymentservice.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final DateTimeFormatter VNP_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId GMT7_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    // VNPAY limit on amount in cents is 12 digits (<= 999,999,999,999), meaning max VND is 9,999,999,999
    private static final BigDecimal MAX_VND_AMOUNT = new BigDecimal("9999999999");

    private final PaymentRepository paymentRepository;
    private final PaymentAuditLogRepository auditLogRepository;
    private final PaymentOutboxService outboxService;
    private final OrderClient orderClient;
    private final VnpayClient vnpayClient;
    private final VnPayProperties vnPayProperties;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public PaymentInitiateResponse initiatePayment(PaymentInitiateRequest request, UUID customerId, String clientIp) {
        UUID orderId = request.getOrderId();
        // Client IP is PII: do not include in application logs
        log.info("Initiating payment intent for orderId={}, customerId={}", orderId, customerId);

        if (clientIp == null || clientIp.isBlank()) {
            throw new ValidationException("Client IP address is required to initiate payment");
        }
        try {
            VnPayProperties.validateLiteralIpAddress(clientIp, "clientIp");
        } catch (Exception ex) {
            throw new ValidationException("Client IP address is invalid or loopback: " + ex.getMessage());
        }

        // 1. Fetch order snapshot from internal Order API
        OrderInternalResponse order = orderClient.getOrder(orderId);
        if (order == null) {
            throw new ResourceNotFoundException("Order not found with ID: " + orderId);
        }

        // 2. Validate ownership, status, currency, and amount
        if (!order.getCustomerId().equals(customerId)) {
            log.warn("Customer {} attempted to pay for order {} owned by another user", customerId, orderId);
            throw new AccessDeniedException("Access denied: You do not own this order");
        }
        if (!"PENDING".equalsIgnoreCase(order.getStatus())) {
            throw new ConflictException("Order is not in PENDING state (current: " + order.getStatus() + ")");
        }
        if (!"VND".equalsIgnoreCase(order.getCurrency())) {
            throw new ValidationException("Unsupported currency: " + order.getCurrency() + ". Only VND is supported.");
        }
        BigDecimal amount = order.getTotalAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Order amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new ValidationException("Order amount must be an exact integer in VND");
        }
        if (amount.compareTo(MAX_VND_AMOUNT) > 0) {
            throw new ValidationException("Order amount exceeds VNPAY maximum limit (max 9,999,999,999 VND)");
        }

        // 3. Preserve one payment per order: Check existing payment for order
        Optional<Payment> existingOpt = paymentRepository.findByOrderId(orderId);
        if (existingOpt.isPresent()) {
            Payment existing = existingOpt.get();
            if (existing.getStatus() == PaymentStatus.SUCCEEDED) {
                throw new ConflictException("Order " + orderId + " has already been paid successfully");
            }
            if (existing.getStatus() == PaymentStatus.FAILED || existing.getStatus() == PaymentStatus.EXPIRED) {
                throw new ConflictException("Order " + orderId + " payment is already in terminal state: " + existing.getStatus());
            }
            if (existing.getStatus() == PaymentStatus.PENDING && existing.getPaymentUrl() != null) {
                log.info("Returning existing active payment URL for orderId={}", orderId);
                return PaymentInitiateResponse.builder()
                        .paymentId(existing.getId())
                        .orderId(existing.getOrderId())
                        .status(existing.getStatus())
                        .paymentUrl(existing.getPaymentUrl())
                        .build();
            }
            throw new ConflictException("Order " + orderId + " has an existing payment in state: " + existing.getStatus());
        }

        // 4. Generate stable unique txnRef and create date
        String txnRef = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String createDate = LocalDateTime.now(GMT7_ZONE).format(VNP_DATE_FORMATTER);

        // 5. Build signed VNPAY URL
        String paymentUrl = vnpayClient.createPaymentUrl(VnPayPaymentUrlRequest.builder()
                .txnRef(txnRef)
                .amount(amount)
                .orderInfo("Thanh toan don hang " + orderId)
                .ipAddress(clientIp)
                .createDate(createDate)
                .build());

        // 6. Persist Payment entity
        Payment payment = Payment.builder()
                .orderId(orderId)
                .customerId(customerId)
                .amount(amount.setScale(0, RoundingMode.UNNECESSARY))
                .currency("VND")
                .provider("VNPAY")
                .status(PaymentStatus.PENDING)
                .providerTxnRef(txnRef)
                .providerCreateDate(createDate)
                .paymentUrl(paymentUrl)
                .totalRefunded(BigDecimal.ZERO)
                .refundInProgress(BigDecimal.ZERO)
                .build();

        try {
            payment = paymentRepository.save(payment);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Concurrent initiate detected for orderId={}, loading committed record", orderId);
            Payment concurrentPayment = paymentRepository.findByOrderId(orderId)
                    .orElseThrow(() -> ex);
            if (concurrentPayment.getStatus() == PaymentStatus.SUCCEEDED) {
                throw new ConflictException("Order " + orderId + " has already been paid successfully");
            }
            if (concurrentPayment.getStatus() == PaymentStatus.FAILED || concurrentPayment.getStatus() == PaymentStatus.EXPIRED) {
                throw new ConflictException("Order " + orderId + " payment is already in terminal state: " + concurrentPayment.getStatus());
            }
            return PaymentInitiateResponse.builder()
                    .paymentId(concurrentPayment.getId())
                    .orderId(concurrentPayment.getOrderId())
                    .status(concurrentPayment.getStatus())
                    .paymentUrl(concurrentPayment.getPaymentUrl())
                    .build();
        }

        // 7. Audit log
        recordAudit(payment.getId(), null, "PAYMENT_INITIATED", null, "PENDING", customerId.toString(), null);

        return PaymentInitiateResponse.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .status(payment.getStatus())
                .paymentUrl(payment.getPaymentUrl())
                .build();
    }

    @Override
    @Transactional
    public VnPayIpnResponse handleIpnCallback(Map<String, String> queryParams) {
        log.info("Received VNPAY IPN callback with params: {}", redactSensitiveParams(queryParams));

        // 1. Verify HMAC-SHA512 checksum
        if (!vnpayClient.verifyIpnSignature(queryParams)) {
            log.warn("IPN rejected: Invalid Checksum");
            return VnPayIpnResponse.invalidChecksum();
        }

        String tmnCode = queryParams.get("vnp_TmnCode");
        String txnRef = queryParams.get("vnp_TxnRef");
        String vnpAmount = queryParams.get("vnp_Amount");
        String responseCode = queryParams.get("vnp_ResponseCode");
        String transactionStatus = queryParams.get("vnp_TransactionStatus");
        String transactionNo = queryParams.get("vnp_TransactionNo");
        String payDate = queryParams.get("vnp_PayDate");

        // 2. Validate merchant terminal code
        if (!Objects.equals(vnPayProperties.getTmnCode(), tmnCode)) {
            log.warn("IPN rejected: vnp_TmnCode mismatch (expected={}, received={})", vnPayProperties.getTmnCode(), tmnCode);
            return VnPayIpnResponse.orderNotFound();
        }

        // 3. Find and lock payment row with PESSIMISTIC_WRITE lock before state/CAS check
        Optional<Payment> paymentOpt = paymentRepository.findByProviderTxnRefForUpdate(txnRef);
        if (paymentOpt.isEmpty()) {
            log.warn("IPN rejected: Order not found for providerTxnRef={}", txnRef);
            return VnPayIpnResponse.orderNotFound();
        }

        Payment payment = paymentOpt.get();

        // 4. Verify callback amount (amount in cents = stored amount * 100)
        try {
            BigDecimal expectedAmountInCents = payment.getAmount().multiply(BigDecimal.valueOf(100));
            BigDecimal receivedAmountInCents = new BigDecimal(vnpAmount);
            if (expectedAmountInCents.compareTo(receivedAmountInCents) != 0) {
                log.warn("IPN rejected: Amount mismatch for txnRef={} (expected={}, received={})",
                        txnRef, expectedAmountInCents, receivedAmountInCents);
                return VnPayIpnResponse.invalidAmount();
            }
        } catch (Exception e) {
            log.warn("IPN rejected: Invalid amount format '{}': {}", vnpAmount, e.getMessage());
            return VnPayIpnResponse.invalidAmount();
        }

        // 5. Idempotent check
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            log.info("IPN duplicate: Payment already confirmed for txnRef={}", txnRef);
            return VnPayIpnResponse.alreadyConfirmed();
        }

        Instant paidAt = parsePayDate(payDate);

        // 6. CAS State Transitions
        if (payment.getStatus() == PaymentStatus.PENDING) {
            if ("00".equals(responseCode) && "00".equals(transactionStatus)) {
                // Success
                payment.setStatus(PaymentStatus.SUCCEEDED);
                payment.setProviderTransactionNo(transactionNo);
                payment.setPaidAt(paidAt);
                paymentRepository.save(payment);

                outboxService.recordPaymentSucceeded(
                        payment.getId(),
                        payment.getOrderId(),
                        payment.getAmount(),
                        payment.getCurrency(),
                        transactionNo,
                        paidAt,
                        null
                );

                recordAudit(payment.getId(), null, "IPN_PAYMENT_SUCCEEDED", "PENDING", "SUCCEEDED", "VNPAY", queryParams);
                log.info("Payment SUCCEEDED for orderId={}, paymentId={}", payment.getOrderId(), payment.getId());
                return VnPayIpnResponse.success();
            } else if ("07".equals(responseCode) || "04".equals(transactionStatus) || "07".equals(transactionStatus)
                    || ("00".equals(responseCode) && "02".equals(transactionStatus))
                    || ("00".equals(transactionStatus) && !"00".equals(responseCode))) {
                // Reversal or suspicious
                payment.setStatus(PaymentStatus.MANUAL_RECONCILIATION_REQUIRED);
                payment.setProviderTransactionNo(transactionNo);
                paymentRepository.save(payment);

                recordAudit(payment.getId(), null, "IPN_PAYMENT_SUSPICIOUS", "PENDING", "MANUAL_RECONCILIATION_REQUIRED", "VNPAY", queryParams);
                log.warn("Payment flagged for MANUAL_RECONCILIATION_REQUIRED for txnRef={}", txnRef);
                return VnPayIpnResponse.success();
            } else if ("02".equals(transactionStatus)) {
                // Failure
                payment.setStatus(PaymentStatus.FAILED);
                payment.setProviderTransactionNo(transactionNo);
                paymentRepository.save(payment);

                outboxService.recordPaymentFailed(
                        payment.getId(),
                        payment.getOrderId(),
                        payment.getAmount(),
                        "VNPAY_ERROR_" + responseCode,
                        null
                );

                recordAudit(payment.getId(), null, "IPN_PAYMENT_FAILED", "PENDING", "FAILED", "VNPAY", queryParams);
                log.info("Payment FAILED for orderId={}, responseCode={}", payment.getOrderId(), responseCode);
                return VnPayIpnResponse.success();
            } else {
                recordAudit(payment.getId(), null, "IPN_PAYMENT_PENDING", "PENDING", "PENDING", "VNPAY", queryParams);
                log.info("Payment remains PENDING for txnRef={} with responseCode={} and transactionStatus={}",
                        txnRef, responseCode, transactionStatus);
                return VnPayIpnResponse.success();
            }
        } else if (payment.getStatus() == PaymentStatus.EXPIRED || payment.getStatus() == PaymentStatus.FAILED) {
            if ("00".equals(responseCode) && "00".equals(transactionStatus)) {
                // Late success
                String oldStatus = payment.getStatus().name();
                payment.setStatus(PaymentStatus.SUCCEEDED);
                payment.setProviderTransactionNo(transactionNo);
                payment.setPaidAt(paidAt);
                paymentRepository.save(payment);

                outboxService.recordPaymentLateSuccess(
                        payment.getId(),
                        payment.getOrderId(),
                        payment.getAmount(),
                        payment.getCurrency(),
                        transactionNo,
                        "LATE_SUCCESS_AFTER_" + oldStatus,
                        null
                );

                recordAudit(payment.getId(), null, "IPN_PAYMENT_LATE_SUCCESS", oldStatus, "SUCCEEDED", "VNPAY", queryParams);
                log.info("Payment LATE_SUCCESS for orderId={}, oldStatus={}", payment.getOrderId(), oldStatus);
                return VnPayIpnResponse.success();
            } else {
                return VnPayIpnResponse.alreadyConfirmed();
            }
        }

        return VnPayIpnResponse.alreadyConfirmed();
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID paymentId, UUID callerId, boolean isPrivilegedStaff) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with ID: " + paymentId));

        if (!isPrivilegedStaff && !payment.getCustomerId().equals(callerId)) {
            throw new AccessDeniedException("Access denied: You do not own this payment resource");
        }

        return PaymentResponse.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .customerId(payment.getCustomerId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .provider(payment.getProvider())
                .status(payment.getStatus())
                .providerTxnRef(payment.getProviderTxnRef())
                .providerTransactionNo(payment.getProviderTransactionNo())
                .paymentUrl(payment.getStatus() == PaymentStatus.PENDING ? payment.getPaymentUrl() : null)
                .paidAt(payment.getPaidAt())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }

    private Instant parsePayDate(String payDate) {
        if (payDate == null || payDate.isBlank()) {
            return Instant.now();
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(payDate, VNP_DATE_FORMATTER);
            return ldt.atZone(GMT7_ZONE).toInstant();
        } catch (Exception e) {
            log.warn("Could not parse vnp_PayDate '{}', using now()", payDate);
            return Instant.now();
        }
    }

    private void recordAudit(UUID paymentId, UUID refundId, String action, String oldStatus, String newStatus, String actor, Map<String, String> rawData) {
        String payloadJson = null;
        if (rawData != null && !rawData.isEmpty()) {
            try {
                payloadJson = objectMapper.writeValueAsString(redactSensitiveParams(rawData));
            } catch (Exception e) {
                log.warn("Failed to serialize audit payload: {}", e.getMessage());
            }
        }

        PaymentAuditLog logEntry = PaymentAuditLog.builder()
                .paymentId(paymentId)
                .refundId(refundId)
                .action(action)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .actor(actor)
                .rawPayload(payloadJson)
                .occurredAt(Instant.now())
                .build();

        auditLogRepository.save(logEntry);
    }

    private Map<String, String> redactSensitiveParams(Map<String, String> params) {
        Map<String, String> redacted = new HashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if ("vnp_SecureHash".equalsIgnoreCase(key)
                    || "vnp_SecureHashType".equalsIgnoreCase(key)
                    || "vnp_IpAddr".equalsIgnoreCase(key)) {
                redacted.put(key, "[REDACTED]");
            } else {
                redacted.put(key, entry.getValue());
            }
        }
        return redacted;
    }
}
