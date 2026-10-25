package com.example.catalogservice.service;

import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.CreateSkuRequest;
import com.example.catalogservice.dto.req.InternalBatchSkuRequest;
import com.example.catalogservice.dto.req.UpdateSkuRequest;
import com.example.catalogservice.dto.resp.InternalBatchSkuResponse;
import com.example.catalogservice.dto.resp.InternalSkuResponse;
import com.example.catalogservice.dto.resp.SkuResponse;
import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.BrandRepository;
import com.example.catalogservice.repository.CategoryRepository;
import com.example.catalogservice.repository.ProductRepository;
import com.example.catalogservice.repository.SkuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkuService {

    private final SkuRepository skuRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final AuditLogService auditLogService;
    private final OutboxService outboxService;

    @Transactional
    public SkuResponse createSku(UUID productId, CreateSkuRequest req, String actorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        String cleanSkuCode = req.getSkuCode().trim().toUpperCase();
        if (skuRepository.existsBySkuCodeAndDeletedAtIsNull(cleanSkuCode)) {
            throw new ConflictException("SKU code already exists: " + cleanSkuCode);
        }

        Sku sku = Sku.builder()
                .productId(product.getId())
                .skuCode(cleanSkuCode)
                .name(req.getName().trim())
                .attributes(req.getAttributes() != null ? req.getAttributes() : "{}")
                .price(req.getPrice())
                .currency(req.getCurrency() != null ? req.getCurrency() : "VND")
                .status(SkuStatus.ACTIVE)
                .build();

        Sku saved = skuRepository.save(sku);
        SkuResponse response = toResponse(saved);

        auditLogService.record(actorId, "USER", "CREATE_SKU", "SKU", saved.getId().toString(), null, response);
        outboxService.recordProductEvent(product.getId(), "UPDATED", product.getName(), product.getStatus().name(), saved.getUpdatedAt().toEpochMilli());

        return response;
    }

    @Transactional
    public SkuResponse updateSku(UUID productId, UUID skuId, UpdateSkuRequest req, String actorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        Sku sku = skuRepository.findByIdAndDeletedAtIsNull(skuId)
                .orElseThrow(() -> new ResourceNotFoundException("SKU not found with id: " + skuId));

        if (!sku.getProductId().equals(productId)) {
            throw new ResourceNotFoundException("SKU " + skuId + " does not belong to product " + productId);
        }

        String cleanSkuCode = req.getSkuCode().trim().toUpperCase();
        if (skuRepository.existsBySkuCodeAndIdNotAndDeletedAtIsNull(cleanSkuCode, skuId)) {
            throw new ConflictException("SKU code already exists: " + cleanSkuCode);
        }

        SkuResponse beforeState = toResponse(sku);

        sku.setSkuCode(cleanSkuCode);
        sku.setName(req.getName().trim());
        sku.setAttributes(req.getAttributes() != null ? req.getAttributes() : "{}");
        sku.setPrice(req.getPrice());
        sku.setCurrency(req.getCurrency() != null ? req.getCurrency() : "VND");
        sku.setUpdatedAt(Instant.now());

        Sku updated = skuRepository.save(sku);
        SkuResponse response = toResponse(updated);

        auditLogService.record(actorId, "USER", "UPDATE_SKU", "SKU", updated.getId().toString(), beforeState, response);
        outboxService.recordProductEvent(product.getId(), "UPDATED", product.getName(), product.getStatus().name(), updated.getUpdatedAt().toEpochMilli());

        return response;
    }

    @Transactional
    public SkuResponse updateSkuStatus(UUID productId, UUID skuId, SkuStatus newStatus, String actorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        Sku sku = skuRepository.findByIdAndDeletedAtIsNull(skuId)
                .orElseThrow(() -> new ResourceNotFoundException("SKU not found with id: " + skuId));

        if (!sku.getProductId().equals(productId)) {
            throw new ResourceNotFoundException("SKU " + skuId + " does not belong to product " + productId);
        }

        SkuResponse beforeState = toResponse(sku);

        sku.setStatus(newStatus);
        sku.setUpdatedAt(Instant.now());

        Sku updated = skuRepository.save(sku);
        SkuResponse response = toResponse(updated);

        auditLogService.record(actorId, "USER", "UPDATE_SKU_STATUS", "SKU", updated.getId().toString(), beforeState, response);
        outboxService.recordProductEvent(product.getId(), "UPDATED", product.getName(), product.getStatus().name(), updated.getUpdatedAt().toEpochMilli());

        return response;
    }

    @Transactional
    public void deleteSku(UUID productId, UUID skuId, String actorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        Sku sku = skuRepository.findByIdAndDeletedAtIsNull(skuId)
                .orElseThrow(() -> new ResourceNotFoundException("SKU not found with id: " + skuId));

        if (!sku.getProductId().equals(productId)) {
            throw new ResourceNotFoundException("SKU " + skuId + " does not belong to product " + productId);
        }

        SkuResponse beforeState = toResponse(sku);
        Instant now = Instant.now();

        sku.setDeletedAt(now);
        sku.setUpdatedAt(now);
        skuRepository.save(sku);

        auditLogService.record(actorId, "USER", "DELETE_SKU", "SKU", skuId.toString(), beforeState, null);
        outboxService.recordProductEvent(product.getId(), "UPDATED", product.getName(), product.getStatus().name(), now.toEpochMilli());
    }

    @Transactional(readOnly = true)
    public List<SkuResponse> getSkusByProductId(UUID productId) {
        return skuRepository.findByProductIdAndDeletedAtIsNull(productId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public InternalSkuResponse getInternalSku(UUID skuId) {
        Sku sku = skuRepository.findByIdAndDeletedAtIsNull(skuId)
                .orElseThrow(() -> new ResourceNotFoundException("SKU not found with id: " + skuId));

        Product product = productRepository.findByIdAndDeletedAtIsNull(sku.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Associated product not found for SKU: " + skuId));

        Category category = categoryRepository.findById(product.getCategoryId()).orElse(null);
        Brand brand = brandRepository.findById(product.getBrandId()).orElse(null);

        return toInternalResponse(sku, product, category, brand);
    }

    @Transactional(readOnly = true)
    public InternalBatchSkuResponse getInternalBatchSkus(InternalBatchSkuRequest req) {
        List<UUID> requestedIds = req.getSkuIds();
        Set<UUID> uniqueIds = new LinkedHashSet<>(requestedIds);

        List<Sku> foundSkus = skuRepository.findByIdInAndDeletedAtIsNull(uniqueIds);
        Map<UUID, Sku> skuMap = foundSkus.stream().collect(Collectors.toMap(Sku::getId, s -> s));

        Set<UUID> productIds = foundSkus.stream().map(Sku::getProductId).collect(Collectors.toSet());
        Map<UUID, Product> productMap = productRepository.findAllById(productIds).stream()
                .filter(p -> p.getDeletedAt() == null)
                .collect(Collectors.toMap(Product::getId, p -> p));

        Set<UUID> categoryIds = productMap.values().stream().map(Product::getCategoryId).collect(Collectors.toSet());
        Map<UUID, Category> categoryMap = categoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, c -> c));

        Set<UUID> brandIds = productMap.values().stream().map(Product::getBrandId).collect(Collectors.toSet());
        Map<UUID, Brand> brandMap = brandRepository.findAllById(brandIds).stream()
                .collect(Collectors.toMap(Brand::getId, b -> b));

        List<InternalSkuResponse> items = new ArrayList<>();
        List<UUID> missingSkuIds = new ArrayList<>();

        for (UUID id : requestedIds) {
            Sku sku = skuMap.get(id);
            if (sku != null) {
                Product product = productMap.get(sku.getProductId());
                if (product != null) {
                    Category category = categoryMap.get(product.getCategoryId());
                    Brand brand = brandMap.get(product.getBrandId());
                    items.add(toInternalResponse(sku, product, category, brand));
                } else {
                    missingSkuIds.add(id);
                }
            } else {
                missingSkuIds.add(id);
            }
        }

        return InternalBatchSkuResponse.builder()
                .items(items)
                .missingSkuIds(missingSkuIds)
                .build();
    }

    private SkuResponse toResponse(Sku sku) {
        return SkuResponse.builder()
                .id(sku.getId())
                .productId(sku.getProductId())
                .skuCode(sku.getSkuCode())
                .name(sku.getName())
                .attributes(sku.getAttributes() != null ? sku.getAttributes().toString() : "{}")
                .price(sku.getPrice())
                .currency(sku.getCurrency())
                .status(sku.getStatus())
                .createdAt(sku.getCreatedAt())
                .updatedAt(sku.getUpdatedAt())
                .build();
    }

    private InternalSkuResponse toInternalResponse(Sku sku, Product product, Category category, Brand brand) {
        String effectiveStatus = computeEffectiveStatus(sku, product, category, brand);
        return InternalSkuResponse.builder()
                .skuId(sku.getId())
                .skuCode(sku.getSkuCode())
                .productId(product.getId())
                .productName(product.getName())
                .skuName(sku.getName())
                .attributes(sku.getAttributes() != null ? sku.getAttributes().toString() : "{}")
                .price(sku.getPrice())
                .currency(sku.getCurrency())
                .status(effectiveStatus)
                .updatedAt(sku.getUpdatedAt())
                .build();
    }

    private String computeEffectiveStatus(Sku sku, Product product, Category category, Brand brand) {
        if (sku.getStatus() != SkuStatus.ACTIVE) {
            return sku.getStatus().name();
        }
        if (product.getStatus() != ProductStatus.ACTIVE) {
            return product.getStatus().name();
        }
        if (category == null || category.getStatus() != CategoryStatus.ACTIVE) {
            return "INACTIVE";
        }
        if (brand == null || brand.getStatus() != BrandStatus.ACTIVE) {
            return "INACTIVE";
        }
        return "ACTIVE";
    }
}
