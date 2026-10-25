package com.example.catalogservice.service;

import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.CreateProductImageRequest;
import com.example.catalogservice.dto.resp.ProductImageResponse;
import com.example.catalogservice.entity.Product;
import com.example.catalogservice.entity.ProductImage;
import com.example.catalogservice.repository.ProductImageRepository;
import com.example.catalogservice.repository.ProductRepository;
import com.example.catalogservice.repository.SkuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImageService {

    private final ProductImageRepository productImageRepository;
    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final AuditLogService auditLogService;
    private final OutboxService outboxService;

    @Transactional
    public ProductImageResponse addImage(UUID productId, CreateProductImageRequest req, String actorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        if (req.getSkuId() != null) {
            skuRepository.findByIdAndDeletedAtIsNull(req.getSkuId())
                    .filter(sku -> sku.getProductId().equals(productId))
                    .orElseThrow(() -> new ResourceNotFoundException("SKU " + req.getSkuId() + " does not belong to product " + productId));
        }

        if (req.isPrimary()) {
            productImageRepository.resetPrimaryForProduct(productId);
        }

        ProductImage image = ProductImage.builder()
                .productId(productId)
                .skuId(req.getSkuId())
                .url(req.getImageUrl().trim())
                .altText(req.getAltText())
                .displayOrder(req.getDisplayOrder())
                .primary(req.isPrimary())
                .build();

        ProductImage saved = productImageRepository.save(image);
        ProductImageResponse response = toResponse(saved);

        auditLogService.record(actorId, "USER", "ADD_IMAGE", "PRODUCT_IMAGE", saved.getId().toString(), null, response);
        outboxService.recordProductEvent(product.getId(), "UPDATED", product.getName(), product.getStatus().name(), Instant.now().toEpochMilli());

        return response;
    }

    @Transactional
    public void deleteImage(UUID productId, UUID imageId, String actorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        ProductImage image = productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Image " + imageId + " does not belong to product " + productId));

        ProductImageResponse beforeState = toResponse(image);
        productImageRepository.delete(image);

        auditLogService.record(actorId, "USER", "DELETE_IMAGE", "PRODUCT_IMAGE", imageId.toString(), beforeState, null);
        outboxService.recordProductEvent(product.getId(), "UPDATED", product.getName(), product.getStatus().name(), Instant.now().toEpochMilli());
    }

    @Transactional(readOnly = true)
    public List<ProductImageResponse> getImagesByProductId(UUID productId) {
        return productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(productId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private ProductImageResponse toResponse(ProductImage image) {
        return ProductImageResponse.builder()
                .id(image.getId())
                .productId(image.getProductId())
                .skuId(image.getSkuId())
                .imageUrl(image.getUrl())
                .altText(image.getAltText())
                .primary(image.isPrimary())
                .displayOrder(image.getDisplayOrder())
                .createdAt(image.getCreatedAt())
                .build();
    }
}
