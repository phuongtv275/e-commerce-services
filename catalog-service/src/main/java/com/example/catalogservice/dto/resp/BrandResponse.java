package com.example.catalogservice.dto.resp;

import com.example.catalogservice.entity.BrandStatus;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandResponse {
    private UUID id;
    private String name;
    private String slug;
    private String description;
    private String logoUrl;
    private BrandStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
