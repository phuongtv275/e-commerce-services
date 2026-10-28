package com.example.catalogservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.catalogservice.dto.req.CreateProductRequest;
import com.example.catalogservice.dto.req.UpdateProductRequest;
import com.example.catalogservice.dto.req.UpdateStatusRequest;
import com.example.catalogservice.dto.resp.ProductResponse;
import com.example.catalogservice.entity.ProductStatus;
import com.example.catalogservice.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping("/admin/products")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
            @Valid @RequestBody CreateProductRequest request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        ProductResponse created = productService.createProduct(request, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Product created successfully", created));
    }

    @GetMapping("/admin/products")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getAllAdminProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size));
        List<ProductResponse> products = productService.getAllProducts(pageable);
        return ResponseEntity.ok(ApiResponse.success(products));
    }

    @GetMapping("/admin/products/{id}")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<ProductResponse>> getAdminProductById(@PathVariable UUID id) {
        ProductResponse product = productService.getProductById(id);
        return ResponseEntity.ok(ApiResponse.success(product));
    }

    @PutMapping("/admin/products/{id}")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProductRequest request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        ProductResponse updated = productService.updateProduct(id, request, actorId);
        return ResponseEntity.ok(ApiResponse.success("Product updated successfully", updated));
    }

    @PatchMapping("/admin/products/{id}/status")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<ProductResponse>> updateProductStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateStatusRequest<ProductStatus> request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        ProductResponse updated = productService.updateProductStatus(id, request.getStatus(), actorId);
        return ResponseEntity.ok(ApiResponse.success("Product status updated successfully", updated));
    }

    @DeleteMapping("/admin/products/{id}")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<Void> deleteProduct(
            @PathVariable UUID id,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        productService.deleteProduct(id, actorId);
        return ResponseEntity.noContent().build();
    }

    private String getActorId(Authentication authentication) {
        return (authentication != null && authentication.getName() != null)
                ? authentication.getName()
                : "anonymous";
    }
}
