package com.example.catalogservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.catalogservice.dto.req.CreateProductImageRequest;
import com.example.catalogservice.dto.resp.ProductImageResponse;
import com.example.catalogservice.service.ProductImageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog/admin/products/{productId}/images")
@RequiredArgsConstructor
public class ProductImageController {

    private final ProductImageService productImageService;

    @PostMapping
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<ProductImageResponse>> addImage(
            @PathVariable UUID productId,
            @Valid @RequestBody CreateProductImageRequest request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        ProductImageResponse added = productImageService.addImage(productId, request, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Image added successfully", added));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<List<ProductImageResponse>>> getImagesByProductId(@PathVariable UUID productId) {
        List<ProductImageResponse> images = productImageService.getImagesByProductId(productId);
        return ResponseEntity.ok(ApiResponse.success(images));
    }

    @DeleteMapping("/{imageId}")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<Void> deleteImage(
            @PathVariable UUID productId,
            @PathVariable UUID imageId,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        productImageService.deleteImage(productId, imageId, actorId);
        return ResponseEntity.noContent().build();
    }

    private String getActorId(Authentication authentication) {
        return (authentication != null && authentication.getName() != null)
                ? authentication.getName()
                : "anonymous";
    }
}
