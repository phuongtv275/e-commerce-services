package com.example.catalogservice.dto.req;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalBatchSkuRequest {

    @NotEmpty(message = "skuIds list cannot be empty")
    @Size(min = 1, max = 100, message = "skuIds list must contain between 1 and 100 IDs")
    private List<UUID> skuIds;
}
