package com.example.inventoryservice.service;

import java.util.UUID;

public record ReservationItemCommand(
        UUID skuId,
        int quantity
) {}
