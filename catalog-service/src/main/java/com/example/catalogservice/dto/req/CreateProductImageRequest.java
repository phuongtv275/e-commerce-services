package com.example.catalogservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateProductImageRequest {

    private UUID skuId;

    @NotBlank(message = "Image URL is required")
    @Pattern(regexp = "^https?://.+", message = "Image URL must be a valid HTTP or HTTPS URL")
    private String imageUrl;

    private String altText;

    @Builder.Default
    private int displayOrder = 0;

    @Builder.Default
    private boolean primary = false;
}
