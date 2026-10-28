package com.example.catalogservice.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.CreateProductRequest;
import com.example.catalogservice.dto.req.UpdateProductRequest;
import com.example.catalogservice.dto.resp.ProductImageResponse;
import com.example.catalogservice.dto.resp.ProductResponse;
import com.example.catalogservice.dto.resp.PublicProductDetailResponse;
import com.example.catalogservice.dto.resp.SkuResponse;
import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final SkuRepository skuRepository;
    private final ProductImageRepository productImageRepository;
    private final AuditLogService auditLogService;
    private final OutboxService outboxService;

    @Transactional
    public ProductResponse createProduct(CreateProductRequest req, String actorId) {
        Category category = categoryRepository.findById(req.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + req.getCategoryId()));
        if (category.getStatus() != CategoryStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Category must be ACTIVE");
        }

        Brand brand = brandRepository.findById(req.getBrandId())
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found with id: " + req.getBrandId()));
        if (brand.getStatus() != BrandStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Brand must be ACTIVE");
        }

        Product product = Product.builder()
                .name(req.getName().trim())
                .description(req.getDescription())
                .metadata(req.getMetadata() != null ? req.getMetadata() : "{}")
                .categoryId(category.getId())
                .brandId(brand.getId())
                .status(ProductStatus.DRAFT)
                .build();

        Product saved = productRepository.save(product);
        ProductResponse response = toResponse(saved, category.getName(), brand.getName());

        auditLogService.record(actorId, "USER", "CREATE_PRODUCT", "PRODUCT", saved.getId().toString(), null, response);
        outboxService.recordProductEvent(saved.getId(), "CREATED", saved.getName(), saved.getStatus().name(), saved.getUpdatedAt().toEpochMilli());

        return response;
    }

    @Transactional
    public ProductResponse updateProduct(UUID id, UpdateProductRequest req, String actorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        Category category = categoryRepository.findById(req.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + req.getCategoryId()));
        if (category.getStatus() != CategoryStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Category must be ACTIVE");
        }

        Brand brand = brandRepository.findById(req.getBrandId())
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found with id: " + req.getBrandId()));
        if (brand.getStatus() != BrandStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Brand must be ACTIVE");
        }

        ProductResponse beforeState = toResponse(product);

        product.setName(req.getName().trim());
        product.setDescription(req.getDescription());
        product.setMetadata(req.getMetadata() != null ? req.getMetadata() : "{}");
        product.setCategoryId(category.getId());
        product.setBrandId(brand.getId());
        product.setUpdatedAt(Instant.now());

        Product updated = productRepository.save(product);
        ProductResponse response = toResponse(updated, category.getName(), brand.getName());

        auditLogService.record(actorId, "USER", "UPDATE_PRODUCT", "PRODUCT", updated.getId().toString(), beforeState, response);
        outboxService.recordProductEvent(updated.getId(), "UPDATED", updated.getName(), updated.getStatus().name(), updated.getUpdatedAt().toEpochMilli());

        return response;
    }

    @Transactional
    public ProductResponse updateProductStatus(UUID id, ProductStatus newStatus, String actorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        ProductResponse beforeState = toResponse(product);

        if (newStatus == ProductStatus.ACTIVE) {
            Category category = categoryRepository.findById(product.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + product.getCategoryId()));
            if (category.getStatus() != CategoryStatus.ACTIVE) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Cannot activate product whose category is not ACTIVE");
            }

            Brand brand = brandRepository.findById(product.getBrandId())
                    .orElseThrow(() -> new ResourceNotFoundException("Brand not found with id: " + product.getBrandId()));
            if (brand.getStatus() != BrandStatus.ACTIVE) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Cannot activate product whose brand is not ACTIVE");
            }

            boolean hasActiveSku = skuRepository.existsByProductIdAndStatusAndDeletedAtIsNull(id, SkuStatus.ACTIVE);
            if (!hasActiveSku) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Cannot activate product without at least one ACTIVE SKU");
            }
        }

        product.setStatus(newStatus);
        product.setUpdatedAt(Instant.now());
        Product updated = productRepository.save(product);
        ProductResponse response = toResponse(updated);

        auditLogService.record(actorId, "USER", "UPDATE_PRODUCT_STATUS", "PRODUCT", updated.getId().toString(), beforeState, response);
        outboxService.recordProductEvent(updated.getId(), "UPDATED", updated.getName(), updated.getStatus().name(), updated.getUpdatedAt().toEpochMilli());

        return response;
    }

    @Transactional
    public void deleteProduct(UUID id, String actorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        ProductResponse beforeState = toResponse(product);
        Instant now = Instant.now();

        product.setDeletedAt(now);
        product.setUpdatedAt(now);
        productRepository.save(product);

        List<Sku> skus = skuRepository.findByProductIdAndDeletedAtIsNull(id);
        for (Sku sku : skus) {
            sku.setDeletedAt(now);
            sku.setUpdatedAt(now);
            skuRepository.save(sku);
        }

        auditLogService.record(actorId, "USER", "DELETE_PRODUCT", "PRODUCT", id.toString(), beforeState, null);
        outboxService.recordProductEvent(id, "DELETED", product.getName(), product.getStatus().name(), now.toEpochMilli());
    }

    @Transactional(readOnly = true)
    public ProductResponse getProductById(UUID id) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        return toResponse(product);
    }

    @Transactional(readOnly = true)
    public PublicProductDetailResponse getPublicProductDetail(UUID id) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));

        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new ResourceNotFoundException("Product not found with id: " + id);
        }

        Category category = categoryRepository.findById(product.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + product.getCategoryId()));
        if (category.getStatus() != CategoryStatus.ACTIVE) {
            throw new ResourceNotFoundException("Product not found with id: " + id);
        }

        Brand brand = brandRepository.findById(product.getBrandId())
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found with id: " + product.getBrandId()));
        if (brand.getStatus() != BrandStatus.ACTIVE) {
            throw new ResourceNotFoundException("Product not found with id: " + id);
        }

        List<SkuResponse> activeSkus = skuRepository.findByProductIdAndStatusAndDeletedAtIsNull(id, SkuStatus.ACTIVE).stream()
                .map(this::toSkuResponse)
                .collect(Collectors.toList());

        List<ProductImageResponse> images = productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(id).stream()
                .map(this::toImageResponse)
                .collect(Collectors.toList());

        return PublicProductDetailResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .metadata(product.getMetadata() != null ? product.getMetadata().toString() : "{}")
                .categoryId(category.getId())
                .categoryName(category.getName())
                .brandId(brand.getId())
                .brandName(brand.getName())
                .skus(activeSkus)
                .images(images)
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts(Pageable pageable) {
        Pageable effectivePageable = pageable != null ? pageable : PageRequest.of(0, 20);
        List<Product> products = productRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc(effectivePageable);
        if (products.isEmpty()) {
            return Collections.emptyList();
        }

        List<UUID> productIds = products.stream().map(Product::getId).collect(Collectors.toList());
        Set<UUID> categoryIds = products.stream().map(Product::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> brandIds = products.stream().map(Product::getBrandId).filter(Objects::nonNull).collect(Collectors.toSet());

        // Batch fetch categories in 1 query
        Map<UUID, String> categoryNames = categoryIds.isEmpty() ? Collections.emptyMap() :
                categoryRepository.findAllById(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Category::getName));

        // Batch fetch brands in 1 query
        Map<UUID, String> brandNames = brandIds.isEmpty() ? Collections.emptyMap() :
                brandRepository.findAllById(brandIds).stream()
                        .collect(Collectors.toMap(Brand::getId, Brand::getName));

        // Batch fetch SKUs in 1 query
        List<Sku> skus = skuRepository.findByProductIdInAndDeletedAtIsNull(productIds);
        Map<UUID, List<SkuResponse>> skusByProductId = skus.stream()
                .map(this::toSkuResponse)
                .collect(Collectors.groupingBy(SkuResponse::getProductId, LinkedHashMap::new, Collectors.toList()));

        // Batch fetch Images in 1 query
        List<ProductImage> images = productImageRepository.findByProductIdInOrderByDisplayOrderAscCreatedAtAsc(productIds);
        Map<UUID, List<ProductImageResponse>> imagesByProductId = images.stream()
                .map(this::toImageResponse)
                .collect(Collectors.groupingBy(ProductImageResponse::getProductId, LinkedHashMap::new, Collectors.toList()));

        return products.stream().map(p -> ProductResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .metadata(p.getMetadata() != null ? p.getMetadata().toString() : "{}")
                .categoryId(p.getCategoryId())
                .categoryName(categoryNames.getOrDefault(p.getCategoryId(), "Unknown"))
                .brandId(p.getBrandId())
                .brandName(brandNames.getOrDefault(p.getBrandId(), "Unknown"))
                .status(p.getStatus())
                .skus(skusByProductId.getOrDefault(p.getId(), Collections.emptyList()))
                .images(imagesByProductId.getOrDefault(p.getId(), Collections.emptyList()))
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build()
        ).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts() {
        return getAllProducts(PageRequest.of(0, 20));
    }

    public ProductResponse toResponse(Product product) {
        String categoryName = categoryRepository.findById(product.getCategoryId())
                .map(Category::getName)
                .orElse("Unknown");
        String brandName = brandRepository.findById(product.getBrandId())
                .map(Brand::getName)
                .orElse("Unknown");
        return toResponse(product, categoryName, brandName);
    }

    public ProductResponse toResponse(Product product, String categoryName, String brandName) {
        List<SkuResponse> skus = skuRepository.findByProductIdAndDeletedAtIsNull(product.getId()).stream()
                .map(this::toSkuResponse)
                .collect(Collectors.toList());

        List<ProductImageResponse> images = productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(product.getId()).stream()
                .map(this::toImageResponse)
                .collect(Collectors.toList());

        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .metadata(product.getMetadata() != null ? product.getMetadata().toString() : "{}")
                .categoryId(product.getCategoryId())
                .categoryName(categoryName)
                .brandId(product.getBrandId())
                .brandName(brandName)
                .status(product.getStatus())
                .skus(skus)
                .images(images)
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }

    public SkuResponse toSkuResponse(Sku sku) {
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

    public ProductImageResponse toImageResponse(ProductImage image) {
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
