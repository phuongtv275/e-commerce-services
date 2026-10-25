package com.example.catalogservice.dto.resp;

import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryTreeNode {
    private UUID id;
    private String name;
    private String slug;
    private String description;
    private int displayOrder;
    @Builder.Default
    private List<CategoryTreeNode> children = new ArrayList<>();
}
