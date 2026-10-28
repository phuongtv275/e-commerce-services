package com.example.catalogservice.config;

import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@Order(1)
@ConditionalOnProperty(name = "catalog.seeder.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class CatalogDataSeeder implements ApplicationRunner {

    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final ProductImageRepository productImageRepository;
    private final SkuStockSnapshotRepository skuStockSnapshotRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (categoryRepository.count() > 0) {
            log.info("Catalog data already exists, skipping seeding.");
            return;
        }

        log.info("Seeding initial catalog data...");
        seedCatalogData();
        log.info("Catalog data seeded successfully.");
    }

    private void seedCatalogData() {
        // 1. Categories (3 sample categories: Điện tử, Thời trang, Gia dụng)
        UUID electronicsId = UUID.randomUUID();
        UUID fashionId = UUID.randomUUID();
        UUID homeLivingId = UUID.randomUUID();

        Category electronics = Category.builder()
                .id(electronicsId)
                .name("Điện tử")
                .slug("dien-tu")
                .description("Thiết bị điện tử và công nghệ")
                .status(CategoryStatus.ACTIVE)
                .displayOrder(1)
                .build();

        Category fashion = Category.builder()
                .id(fashionId)
                .name("Thời trang")
                .slug("thoi-trang")
                .description("Thời trang nam nữ và phụ kiện")
                .status(CategoryStatus.ACTIVE)
                .displayOrder(2)
                .build();

        Category homeLiving = Category.builder()
                .id(homeLivingId)
                .name("Gia dụng")
                .slug("gia-dung")
                .description("Đồ gia dụng và đời sống")
                .status(CategoryStatus.ACTIVE)
                .displayOrder(3)
                .build();

        categoryRepository.saveAll(List.of(electronics, fashion, homeLiving));

        // 2. Brands (3 sample brands: Apple, Samsung, Sony)
        UUID appleId = UUID.randomUUID();
        UUID samsungId = UUID.randomUUID();
        UUID sonyId = UUID.randomUUID();

        Brand apple = Brand.builder()
                .id(appleId)
                .name("Apple")
                .slug("apple")
                .description("Thương hiệu Apple")
                .logoUrl("https://example.com/logos/apple.png")
                .status(BrandStatus.ACTIVE)
                .build();

        Brand samsung = Brand.builder()
                .id(samsungId)
                .name("Samsung")
                .slug("samsung")
                .description("Thương hiệu Samsung")
                .logoUrl("https://example.com/logos/samsung.png")
                .status(BrandStatus.ACTIVE)
                .build();

        Brand sony = Brand.builder()
                .id(sonyId)
                .name("Sony")
                .slug("sony")
                .description("Thương hiệu Sony")
                .logoUrl("https://example.com/logos/sony.png")
                .status(BrandStatus.ACTIVE)
                .build();

        brandRepository.saveAll(List.of(apple, samsung, sony));

        // 3. Product 1: iPhone 15 Pro Max
        UUID iphone15Id = UUID.randomUUID();
        Product iphone15 = Product.builder()
                .id(iphone15Id)
                .name("iPhone 15 Pro Max")
                .description("Điện thoại thông minh iPhone 15 Pro Max cao cấp")
                .categoryId(electronicsId)
                .brandId(appleId)
                .status(ProductStatus.ACTIVE)
                .metadata("{\"origin\": \"USA\", \"warranty\": \"12 months\"}")
                .build();
        productRepository.save(iphone15);

        // Product 1 primary image
        ProductImage iphoneImage = ProductImage.builder()
                .id(UUID.randomUUID())
                .productId(iphone15Id)
                .url("https://example.com/images/iphone-15-pro-max.png")
                .altText("iPhone 15 Pro Max Primary Image")
                .primary(true)
                .displayOrder(1)
                .build();
        productImageRepository.save(iphoneImage);

        // Product 1 SKUs
        UUID iphoneSku256Id = UUID.randomUUID();
        UUID iphoneSku512Id = UUID.randomUUID();

        Sku iphoneSku256 = Sku.builder()
                .id(iphoneSku256Id)
                .productId(iphone15Id)
                .skuCode("IPHONE15-PM-256GB")
                .name("iPhone 15 Pro Max 256GB")
                .price(new BigDecimal("29990000.0000"))
                .currency("VND")
                .status(SkuStatus.ACTIVE)
                .attributes("{\"storage\": \"256GB\", \"color\": \"Natural Titanium\"}")
                .build();

        Sku iphoneSku512 = Sku.builder()
                .id(iphoneSku512Id)
                .productId(iphone15Id)
                .skuCode("IPHONE15-PM-512GB")
                .name("iPhone 15 Pro Max 512GB")
                .price(new BigDecimal("34990000.0000"))
                .currency("VND")
                .status(SkuStatus.ACTIVE)
                .attributes("{\"storage\": \"512GB\", \"color\": \"Blue Titanium\"}")
                .build();

        skuRepository.saveAll(List.of(iphoneSku256, iphoneSku512));

        // 4. Product 2: Samsung Galaxy S24 Ultra
        UUID galaxyS24Id = UUID.randomUUID();
        Product galaxyS24 = Product.builder()
                .id(galaxyS24Id)
                .name("Samsung Galaxy S24 Ultra")
                .description("Điện thoại Samsung Galaxy S24 Ultra tích hợp Galaxy AI")
                .categoryId(electronicsId)
                .brandId(samsungId)
                .status(ProductStatus.ACTIVE)
                .metadata("{\"origin\": \"Korea\", \"warranty\": \"12 months\"}")
                .build();
        productRepository.save(galaxyS24);

        // Product 2 primary image
        ProductImage galaxyImage = ProductImage.builder()
                .id(UUID.randomUUID())
                .productId(galaxyS24Id)
                .url("https://example.com/images/samsung-s24-ultra.png")
                .altText("Samsung Galaxy S24 Ultra Primary Image")
                .primary(true)
                .displayOrder(1)
                .build();
        productImageRepository.save(galaxyImage);

        // Product 2 SKU
        UUID galaxySku256Id = UUID.randomUUID();
        Sku galaxySku256 = Sku.builder()
                .id(galaxySku256Id)
                .productId(galaxyS24Id)
                .skuCode("SAMSUNGS24-U-256GB")
                .name("Samsung Galaxy S24 Ultra 256GB")
                .price(new BigDecimal("28990000.0000"))
                .currency("VND")
                .status(SkuStatus.ACTIVE)
                .attributes("{\"storage\": \"256GB\", \"color\": \"Titanium Black\"}")
                .build();
        skuRepository.save(galaxySku256);

        // 5. SKU stock snapshots (inStock = true, inventoryVersion = 1L)
        Instant now = Instant.now();
        List<SkuStockSnapshot> snapshots = List.of(
                SkuStockSnapshot.builder()
                        .skuId(iphoneSku256Id)
                        .inStock(true)
                        .inventoryVersion(1L)
                        .updatedAt(now)
                        .build(),
                SkuStockSnapshot.builder()
                        .skuId(iphoneSku512Id)
                        .inStock(true)
                        .inventoryVersion(1L)
                        .updatedAt(now)
                        .build(),
                SkuStockSnapshot.builder()
                        .skuId(galaxySku256Id)
                        .inStock(true)
                        .inventoryVersion(1L)
                        .updatedAt(now)
                        .build()
        );
        skuStockSnapshotRepository.saveAll(snapshots);
    }
}
