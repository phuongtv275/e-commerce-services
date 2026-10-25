package com.example.inventoryservice.dto;

import java.util.UUID;

public record ReservationItemResponse(
        UUID skuId,
        int quantity
) {}
