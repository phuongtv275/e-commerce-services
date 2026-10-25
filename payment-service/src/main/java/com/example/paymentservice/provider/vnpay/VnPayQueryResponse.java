package com.example.paymentservice.provider.vnpay;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VnPayQueryResponse {
    @JsonProperty("vnp_ResponseId")
    private String responseId;

    @JsonProperty("vnp_Command")
    private String command;

    @JsonProperty("vnp_ResponseCode")
    private String responseCode;

    @JsonProperty("vnp_Message")
    private String message;

    @JsonProperty("vnp_TmnCode")
    private String tmnCode;

    @JsonProperty("vnp_TxnRef")
    private String txnRef;

    @JsonProperty("vnp_Amount")
    private BigDecimal amount;

    @JsonProperty("vnp_OrderInfo")
    private String orderInfo;

    @JsonProperty("vnp_BankCode")
    private String bankCode;

    @JsonProperty("vnp_PayDate")
    private String payDate;

    @JsonProperty("vnp_TransactionNo")
    private String transactionNo;

    @JsonProperty("vnp_TransactionType")
    private String transactionType;

    @JsonProperty("vnp_TransactionStatus")
    private String transactionStatus;

    @JsonProperty("vnp_PromotionCode")
    private String promotionCode;

    @JsonProperty("vnp_PromotionAmount")
    private String promotionAmount;

    @JsonProperty("vnp_SecureHash")
    private String secureHash;
}
