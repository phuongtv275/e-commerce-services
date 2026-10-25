package com.example.catalogservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.catalogservice.dto.req.CreateCategoryRequest;
import com.example.catalogservice.dto.req.UpdateCategoryRequest;
import com.example.catalogservice.dto.req.UpdateStatusRequest;
import com.example.catalogservice.dto.resp.CategoryResponse;
import com.example.catalogservice.dto.resp.CategoryTreeNode;
import com.example.catalogservice.entity.CategoryStatus;
import com.example.catalogservice.service.CategoryService;
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
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryTreeNode>>> getPublicCategoryTree() {
        List<CategoryTreeNode> tree = categoryService.getPublicCategoryTree();
        return ResponseEntity.ok(ApiResponse.success(tree));
    }

    @GetMapping("/categories/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> getPublicCategoryById(@PathVariable UUID id) {
        CategoryResponse category = categoryService.getCategoryById(id);
        return ResponseEntity.ok(ApiResponse.success(category));
    }

    @PostMapping("/admin/categories")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @Valid @RequestBody CreateCategoryRequest request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        CategoryResponse created = categoryService.createCategory(request, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Category created successfully", created));
    }

    @GetMapping("/admin/categories")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getAllCategories() {
        List<CategoryResponse> categories = categoryService.getAllCategories();
        return ResponseEntity.ok(ApiResponse.success(categories));
    }

    @GetMapping("/admin/categories/{id}")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<CategoryResponse>> getAdminCategoryById(@PathVariable UUID id) {
        CategoryResponse category = categoryService.getCategoryById(id);
        return ResponseEntity.ok(ApiResponse.success(category));
    }

    @PutMapping("/admin/categories/{id}")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCategoryRequest request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        CategoryResponse updated = categoryService.updateCategory(id, request, actorId);
        return ResponseEntity.ok(ApiResponse.success("Category updated successfully", updated));
    }

    @PatchMapping("/admin/categories/{id}/status")
    @PreAuthorize("hasAnyRole('CATALOG_STAFF', 'ADMINISTRATOR')")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategoryStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateStatusRequest<CategoryStatus> request,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        CategoryResponse updated = categoryService.updateCategoryStatus(id, request.getStatus(), actorId);
        return ResponseEntity.ok(ApiResponse.success("Category status updated successfully", updated));
    }

    @DeleteMapping("/admin/categories/{id}")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public ResponseEntity<Void> deleteCategory(
            @PathVariable UUID id,
            Authentication authentication) {
        String actorId = getActorId(authentication);
        categoryService.deleteCategory(id, actorId);
        return ResponseEntity.noContent().build();
    }

    private String getActorId(Authentication authentication) {
        return (authentication != null && authentication.getName() != null)
                ? authentication.getName()
                : "anonymous";
    }
}
