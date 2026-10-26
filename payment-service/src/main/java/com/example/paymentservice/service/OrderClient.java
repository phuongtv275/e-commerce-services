package com.example.paymentservice.service;

import com.example.paymentservice.dto.OrderInternalResponse;

import java.util.UUID;

public interface OrderClient {

    OrderInternalResponse getOrder(UUID orderId);
}
