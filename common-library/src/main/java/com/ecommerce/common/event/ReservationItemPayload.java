package com.ecommerce.common.event;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationItemPayload {
    private UUID skuId;
    private int quantity;
}
