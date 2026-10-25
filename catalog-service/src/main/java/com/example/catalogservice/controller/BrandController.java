package com.example.catalogservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.catalogservice.dto.req.CreateBrandRequest;
import com.example.catalogservice.dto.req.UpdateBrandRequest;
import com.example.catalogservice.dto.req.UpdateStatusRequest;
import com.example.catalogservice.dto.resp.BrandResponse;
import com.example.catalogservice.entity.BrandStatus;
import com.example.catalogservice.service.BrandService;
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
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    @GetMapping("/brands")
    public ResponseEntity<ApiResponse<List<BrandResponse>>> getPublicBrands() {
        List<BrandResponse> brands = brandService.getPublicBrands();
        return ResponseEntity.ok(ApiResponse.success(brands));
    }

    @GetMapping("/brands/{id}")
    public ResponseEntity<ApiResponse<BrandResponse>> getPublicBrandById(@PathVariable UUID id) {
        BrandResponse brand = brandService.getBrandById(id);
        return ResponseEntity.ok(ApiResponse.success(brand));
    }

    @PostMapping("/admin/brands")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<BrandResponse>> createBrand(
            @Valid @RequestBody CreateBrandRequest request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        BrandResponse created = brandService.createBrand(request, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Brand created successfully", created));
    }

    @GetMapping("/admin/brands")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<List<BrandResponse>>> getAllBrands() {
        List<BrandResponse> brands = brandService.getAllBrands();
        return ResponseEntity.ok(ApiResponse.success(brands));
    }

    @GetMapping("/admin/brands/{id}")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<BrandResponse>> getAdminBrandById(@PathVariable UUID id) {
        BrandResponse brand = brandService.getBrandById(id);
        return ResponseEntity.ok(ApiResponse.success(brand));
    }

    @PutMapping("/admin/brands/{id}")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<BrandResponse>> updateBrand(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateBrandRequest request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        BrandResponse updated = brandService.updateBrand(id, request, actorId);
        return ResponseEntity.ok(ApiResponse.success("Brand updated successfully", updated));
    }

    @PatchMapping("/admin/brands/{id}/status")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<BrandResponse>> updateBrandStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateStatusRequest<BrandStatus> request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        BrandResponse updated = brandService.updateBrandStatus(id, request.getStatus(), actorId);
        return ResponseEntity.ok(ApiResponse.success("Brand status updated successfully", updated));
    }

    @DeleteMapping("/admin/brands/{id}")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public ResponseEntity<Void> deleteBrand(
            @PathVariable UUID id,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        brandService.deleteBrand(id, actorId);
        return ResponseEntity.noContent().build();
    }

    private String getActorId(Authentication authentication) {
        return (authentication != null && authentication.getName() != null)
                ? authentication.getName()
                : "anonymous";
    }
}
