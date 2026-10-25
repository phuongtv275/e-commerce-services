package com.example.catalogservice.dto.resp;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalBatchSkuResponse {
    private List<InternalSkuResponse> items;
    private List<UUID> missingSkuIds;
}
