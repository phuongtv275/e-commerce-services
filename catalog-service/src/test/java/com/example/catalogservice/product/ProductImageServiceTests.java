package com.example.catalogservice.product;

import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.CreateProductImageRequest;
import com.example.catalogservice.dto.resp.ProductImageResponse;
import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.*;
import com.example.catalogservice.service.ProductImageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "management.health.elasticsearch.enabled=false"
})
class ProductImageServiceTests {

    @Autowired
    private ProductImageService productImageService;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SkuRepository skuRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private BrandRepository brandRepository;

    private Product product;

    @BeforeEach
    void setUp() {
        productImageRepository.deleteAll();
        skuRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        brandRepository.deleteAll();

        Category category = categoryRepository.save(Category.builder()
                .name("Audio")
                .slug("audio")
                .status(CategoryStatus.ACTIVE)
                .build());

        Brand brand = brandRepository.save(Brand.builder()
                .name("Sony")
                .slug("sony")
                .status(BrandStatus.ACTIVE)
                .build());

        product = productRepository.save(Product.builder()
                .name("WH-1000XM5")
                .categoryId(category.getId())
                .brandId(brand.getId())
                .status(ProductStatus.DRAFT)
                .build());
    }

    @AfterEach
    void tearDown() {
        productImageRepository.deleteAll();
        skuRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        brandRepository.deleteAll();
    }

    @Test
    @DisplayName("Positive: Add image and atomically reset previous primary image")
    void testAddImage_PrimaryReset() {
        CreateProductImageRequest req1 = CreateProductImageRequest.builder()
                .imageUrl("https://example.com/img1.jpg")
                .altText("Front")
                .primary(true)
                .displayOrder(1)
                .build();
        ProductImageResponse img1 = productImageService.addImage(product.getId(), req1, "admin-1");
        assertThat(img1.isPrimary()).isTrue();

        CreateProductImageRequest req2 = CreateProductImageRequest.builder()
                .imageUrl("https://example.com/img2.jpg")
                .altText("Side")
                .primary(true)
                .displayOrder(2)
                .build();
        ProductImageResponse img2 = productImageService.addImage(product.getId(), req2, "admin-1");
        assertThat(img2.isPrimary()).isTrue();

        // Verify img1 is no longer primary
        List<ProductImage> allImages = productImageRepository.findByProductIdOrderByDisplayOrderAscCreatedAtAsc(product.getId());
        ProductImage refreshedImg1 = allImages.stream().filter(i -> i.getId().equals(img1.getId())).findFirst().orElseThrow();
        ProductImage refreshedImg2 = allImages.stream().filter(i -> i.getId().equals(img2.getId())).findFirst().orElseThrow();

        assertThat(refreshedImg1.isPrimary()).isFalse();
        assertThat(refreshedImg2.isPrimary()).isTrue();
    }

    @Test
    @DisplayName("Negative: Add image with skuId of a different product throws ResourceNotFoundException (404)")
    void testAddImage_MismatchedSkuId_ThrowsNotFound() {
        Product otherProduct = productRepository.save(Product.builder()
                .name("Other Product")
                .categoryId(product.getCategoryId())
                .brandId(product.getBrandId())
                .status(ProductStatus.DRAFT)
                .build());

        Sku otherSku = skuRepository.save(Sku.builder()
                .productId(otherProduct.getId())
                .skuCode("OTHER-SKU")
                .name("Other Sku")
                .price(java.math.BigDecimal.TEN)
                .build());

        CreateProductImageRequest req = CreateProductImageRequest.builder()
                .skuId(otherSku.getId())
                .imageUrl("https://example.com/img.jpg")
                .build();

        assertThatThrownBy(() -> productImageService.addImage(product.getId(), req, "admin-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("does not belong to product");
    }

    @Test
    @DisplayName("Positive: Delete image successfully removes it")
    void testDeleteImage_Success() {
        CreateProductImageRequest req = CreateProductImageRequest.builder()
                .imageUrl("https://example.com/img1.jpg")
                .build();
        ProductImageResponse img = productImageService.addImage(product.getId(), req, "admin-1");

        productImageService.deleteImage(product.getId(), img.getId(), "admin-1");
        assertThat(productImageRepository.findById(img.getId())).isEmpty();
    }
}
