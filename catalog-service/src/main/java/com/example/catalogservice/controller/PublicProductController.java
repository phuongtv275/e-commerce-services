package com.example.catalogservice.controller;

import com.ecommerce.common.response.ApiResponse;
import com.example.catalogservice.dto.req.SearchProductRequest;
import com.example.catalogservice.dto.resp.PublicProductDetailResponse;
import com.example.catalogservice.dto.resp.SearchProductResponse;
import com.example.catalogservice.elasticsearch.service.ProductSearchService;
import com.example.catalogservice.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
public class PublicProductController {

    private final ProductService productService;
    private final ProductSearchService productSearchService;

    @GetMapping("/products/{id}")
    public ResponseEntity<ApiResponse<PublicProductDetailResponse>> getProductDetail(@PathVariable UUID id) {
        PublicProductDetailResponse response = productService.getPublicProductDetail(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<SearchProductResponse>> searchProducts(@Valid SearchProductRequest request) {
        SearchProductResponse response = productSearchService.search(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
