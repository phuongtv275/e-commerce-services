package com.example.catalogservice.service;

import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.CreateBrandRequest;
import com.example.catalogservice.dto.req.UpdateBrandRequest;
import com.example.catalogservice.dto.resp.BrandResponse;
import com.example.catalogservice.entity.Brand;
import com.example.catalogservice.entity.BrandStatus;
import com.example.catalogservice.repository.BrandRepository;
import com.example.catalogservice.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrandService {

    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final AuditLogService auditLogService;
    private final OutboxService outboxService;

    @Transactional
    public BrandResponse createBrand(CreateBrandRequest req, String actorId) {
        String cleanSlug = req.getSlug().trim().toLowerCase();
        if (brandRepository.existsBySlug(cleanSlug)) {
            throw new ConflictException("Brand slug already exists: " + cleanSlug);
        }

        Brand brand = Brand.builder()
                .name(req.getName().trim())
                .slug(cleanSlug)
                .description(req.getDescription())
                .logoUrl(req.getLogoUrl())
                .status(BrandStatus.ACTIVE)
                .build();

        Brand saved = brandRepository.save(brand);
        auditLogService.record(actorId, "USER", "CREATE_BRAND", "BRAND", saved.getId().toString(), null, toResponse(saved));
        return toResponse(saved);
    }

    @Transactional
    public BrandResponse updateBrand(UUID id, UpdateBrandRequest req, String actorId) {
        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found with id: " + id));

        BrandResponse beforeState = toResponse(brand);
        String cleanSlug = req.getSlug().trim().toLowerCase();

        if (brandRepository.existsBySlugAndIdNot(cleanSlug, id)) {
            throw new ConflictException("Brand slug already exists: " + cleanSlug);
        }

        brand.setName(req.getName().trim());
        brand.setSlug(cleanSlug);
        brand.setDescription(req.getDescription());
        brand.setLogoUrl(req.getLogoUrl());

        Brand updated = brandRepository.save(brand);
        auditLogService.record(actorId, "USER", "UPDATE_BRAND", "BRAND", updated.getId().toString(), beforeState, toResponse(updated));

        if (!beforeState.getName().equals(updated.getName())) {
            emitProductEventsForBrand(updated.getId());
        }

        return toResponse(updated);
    }

    @Transactional
    public BrandResponse updateBrandStatus(UUID id, BrandStatus newStatus, String actorId) {
        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found with id: " + id));

        BrandResponse beforeState = toResponse(brand);
        brand.setStatus(newStatus);
        Brand updated = brandRepository.save(brand);
        auditLogService.record(actorId, "USER", "UPDATE_BRAND_STATUS", "BRAND", updated.getId().toString(), beforeState, toResponse(updated));

        if (beforeState.getStatus() != updated.getStatus()) {
            emitProductEventsForBrand(updated.getId());
        }

        return toResponse(updated);
    }

    private void emitProductEventsForBrand(UUID brandId) {
        int pageSize = 100;
        int maxBatches = 10; // Bounded fan-out up to 1,000 products per transaction
        long now = System.currentTimeMillis();
        for (int page = 0; page < maxBatches; page++) {
            List<com.example.catalogservice.entity.Product> products =
                    productRepository.findByBrandIdAndDeletedAtIsNullOrderByIdAsc(brandId, org.springframework.data.domain.PageRequest.of(page, pageSize));
            if (products.isEmpty()) {
                break;
            }
            for (com.example.catalogservice.entity.Product product : products) {
                outboxService.recordProductEvent(product.getId(), "UPDATED", product.getName(), product.getStatus().name(), now);
            }
            if (products.size() < pageSize) {
                break;
            }
        }
    }


    @Transactional
    public void deleteBrand(UUID id, String actorId) {
        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found with id: " + id));

        if (productRepository.existsByBrandIdAndDeletedAtIsNull(id)) {
            throw new ConflictException("Cannot delete brand with associated products");
        }

        BrandResponse beforeState = toResponse(brand);
        brandRepository.delete(brand);
        auditLogService.record(actorId, "USER", "DELETE_BRAND", "BRAND", id.toString(), beforeState, null);
    }

    @Transactional(readOnly = true)
    public BrandResponse getBrandById(UUID id) {
        return brandRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<BrandResponse> getPublicBrands() {
        return brandRepository.findByStatusOrderByNameAsc(BrandStatus.ACTIVE).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<BrandResponse> getAllBrands() {
        return brandRepository.findAllByOrderByNameAsc().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private BrandResponse toResponse(Brand brand) {
        return BrandResponse.builder()
                .id(brand.getId())
                .name(brand.getName())
                .slug(brand.getSlug())
                .description(brand.getDescription())
                .logoUrl(brand.getLogoUrl())
                .status(brand.getStatus())
                .createdAt(brand.getCreatedAt())
                .updatedAt(brand.getUpdatedAt())
                .build();
    }
}
