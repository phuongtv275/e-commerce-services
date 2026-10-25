package com.example.catalogservice.dto.resp;

import com.example.catalogservice.entity.CategoryStatus;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryResponse {
    private UUID id;
    private String name;
    private String slug;
    private String description;
    private UUID parentId;
    private CategoryStatus status;
    private int displayOrder;
    private Instant createdAt;
    private Instant updatedAt;
}
