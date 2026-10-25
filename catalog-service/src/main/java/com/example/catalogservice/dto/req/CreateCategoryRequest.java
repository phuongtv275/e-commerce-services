package com.example.catalogservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCategoryRequest {

    @NotBlank(message = "Category name cannot be blank")
    @Size(max = 255, message = "Category name must not exceed 255 characters")
    private String name;

    @NotBlank(message = "Category slug cannot be blank")
    @Size(max = 255, message = "Category slug must not exceed 255 characters")
    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Category slug must be lowercase alphanumeric with hyphens")
    private String slug;

    private String description;

    private UUID parentId;

    @Builder.Default
    private Integer displayOrder = 0;
}
