package com.example.catalogservice.product;

import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.CreateSkuRequest;
import com.example.catalogservice.dto.req.InternalBatchSkuRequest;
import com.example.catalogservice.dto.req.UpdateSkuRequest;
import com.example.catalogservice.dto.resp.InternalBatchSkuResponse;
import com.example.catalogservice.dto.resp.InternalSkuResponse;
import com.example.catalogservice.dto.resp.SkuResponse;
import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.*;
import com.example.catalogservice.service.SkuService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
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
class SkuServiceTests {

    @Autowired
    private SkuService skuService;

    @Autowired
    private SkuRepository skuRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private BrandRepository brandRepository;

    private Product product;

    @BeforeEach
    void setUp() {
        skuRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        brandRepository.deleteAll();

        Category category = categoryRepository.save(Category.builder()
                .name("Smartphones")
                .slug("smartphones")
                .status(CategoryStatus.ACTIVE)
                .build());

        Brand brand = brandRepository.save(Brand.builder()
                .name("Apple")
                .slug("apple")
                .status(BrandStatus.ACTIVE)
                .build());

        product = productRepository.save(Product.builder()
                .name("iPhone 16 Pro")
                .categoryId(category.getId())
                .brandId(brand.getId())
                .status(ProductStatus.DRAFT)
                .build());
    }

    @AfterEach
    void tearDown() {
        skuRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        brandRepository.deleteAll();
    }

    @Test
    @DisplayName("Positive: Create SKU with VND price and attributes succeeds")
    void testCreateSku_Success() {
        CreateSkuRequest req = CreateSkuRequest.builder()
                .skuCode("IPHONE16P-128-NAT")
                .name("iPhone 16 Pro 128GB Natural Titanium")
                .attributes("{\"color\":\"Natural Titanium\",\"storage\":\"128GB\"}")
                .price(new BigDecimal("28990000"))
                .currency("VND")
                .build();

        SkuResponse response = skuService.createSku(product.getId(), req, "admin-1");

        assertThat(response.getId()).isNotNull();
        assertThat(response.getSkuCode()).isEqualTo("IPHONE16P-128-NAT");
        assertThat(response.getPrice()).isEqualByComparingTo("28990000");
        assertThat(response.getStatus()).isEqualTo(SkuStatus.ACTIVE);
    }

    @Test
    @DisplayName("Negative: Create SKU with duplicate SKU code throws ConflictException (409)")
    void testCreateSku_DuplicateCode_ThrowsConflict() {
        CreateSkuRequest req1 = CreateSkuRequest.builder()
                .skuCode("IPHONE16P-128-NAT")
                .name("iPhone 16 Pro 128GB Natural Titanium")
                .price(new BigDecimal("28990000"))
                .build();
        skuService.createSku(product.getId(), req1, "admin-1");

        CreateSkuRequest req2 = CreateSkuRequest.builder()
                .skuCode("iphone16p-128-nat") // case-insensitive check
                .name("Duplicate SKU")
                .price(new BigDecimal("28990000"))
                .build();

        assertThatThrownBy(() -> skuService.createSku(product.getId(), req2, "admin-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("Negative: Update SKU with duplicate SKU code of another SKU throws ConflictException (409)")
    void testUpdateSku_DuplicateCode_ThrowsConflict() {
        SkuResponse s1 = skuService.createSku(product.getId(), CreateSkuRequest.builder()
                .skuCode("SKU-1")
                .name("SKU 1")
                .price(new BigDecimal("10000"))
                .build(), "admin-1");

        SkuResponse s2 = skuService.createSku(product.getId(), CreateSkuRequest.builder()
                .skuCode("SKU-2")
                .name("SKU 2")
                .price(new BigDecimal("20000"))
                .build(), "admin-1");

        UpdateSkuRequest updateReq = UpdateSkuRequest.builder()
                .skuCode("SKU-1") // duplicate with s1
                .name("Updated SKU 2")
                .price(new BigDecimal("20000"))
                .build();

        assertThatThrownBy(() -> skuService.updateSku(product.getId(), s2.getId(), updateReq, "admin-1"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Negative: Update SKU that does not belong to the product throws ResourceNotFoundException (404)")
    void testUpdateSku_MismatchedProduct_ThrowsNotFound() {
        SkuResponse s1 = skuService.createSku(product.getId(), CreateSkuRequest.builder()
                .skuCode("SKU-1")
                .name("SKU 1")
                .price(new BigDecimal("10000"))
                .build(), "admin-1");

        UUID otherProductId = UUID.randomUUID();
        UpdateSkuRequest updateReq = UpdateSkuRequest.builder()
                .skuCode("SKU-1")
                .name("Updated")
                .price(new BigDecimal("10000"))
                .build();

        assertThatThrownBy(() -> skuService.updateSku(otherProductId, s1.getId(), updateReq, "admin-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Positive: Internal single and batch SKU querying returns expected contracts with effective status")
    void testInternalSkuQuerying() {
        // Activate product so it is sellable
        product.setStatus(ProductStatus.ACTIVE);
        productRepository.save(product);

        SkuResponse s1 = skuService.createSku(product.getId(), CreateSkuRequest.builder()
                .skuCode("SKU-A")
                .name("SKU A")
                .price(new BigDecimal("150000"))
                .build(), "admin-1");

        SkuResponse s2 = skuService.createSku(product.getId(), CreateSkuRequest.builder()
                .skuCode("SKU-B")
                .name("SKU B")
                .price(new BigDecimal("250000"))
                .build(), "admin-1");

        // Single query
        InternalSkuResponse single = skuService.getInternalSku(s1.getId());
        assertThat(single.getSkuCode()).isEqualTo("SKU-A");
        assertThat(single.getProductName()).isEqualTo("iPhone 16 Pro");
        assertThat(single.getPrice()).isEqualByComparingTo("150000");
        assertThat(single.getStatus()).isEqualTo("ACTIVE");

        // Batch query with missing ID
        UUID nonExistentId = UUID.randomUUID();
        InternalBatchSkuRequest batchReq = InternalBatchSkuRequest.builder()
                .skuIds(List.of(s2.getId(), nonExistentId, s1.getId()))
                .build();

        InternalBatchSkuResponse batchRes = skuService.getInternalBatchSkus(batchReq);
        assertThat(batchRes.getItems()).hasSize(2);
        assertThat(batchRes.getItems().get(0).getSkuCode()).isEqualTo("SKU-B");
        assertThat(batchRes.getItems().get(0).getStatus()).isEqualTo("ACTIVE");
        assertThat(batchRes.getItems().get(1).getSkuCode()).isEqualTo("SKU-A");
        assertThat(batchRes.getItems().get(1).getStatus()).isEqualTo("ACTIVE");
        assertThat(batchRes.getMissingSkuIds()).containsExactly(nonExistentId);
    }

    @Test
    @DisplayName("Negative: Internal SKU response enforces product/category/brand ACTIVE and returns effective unsellable status")
    void testEffectiveSaleStatusInInternalResponse() {
        SkuResponse sku = skuService.createSku(product.getId(), CreateSkuRequest.builder()
                .skuCode("SKU-EFFECTIVE")
                .name("Effective SKU")
                .price(new BigDecimal("100000"))
                .build(), "admin-1");

        // 1. Newly created SKU is ACTIVE, but product is DRAFT -> effective status is DRAFT
        InternalSkuResponse resDraft = skuService.getInternalSku(sku.getId());
        assertThat(resDraft.getStatus()).isEqualTo("DRAFT");

        // 2. Activate product -> effective status becomes ACTIVE
        product.setStatus(ProductStatus.ACTIVE);
        productRepository.save(product);
        InternalSkuResponse resActive = skuService.getInternalSku(sku.getId());
        assertThat(resActive.getStatus()).isEqualTo("ACTIVE");

        // 3. Deactivate category -> effective status becomes INACTIVE
        Category category = categoryRepository.findById(product.getCategoryId()).orElseThrow();
        category.setStatus(CategoryStatus.INACTIVE);
        categoryRepository.save(category);
        InternalSkuResponse resCatInactive = skuService.getInternalSku(sku.getId());
        assertThat(resCatInactive.getStatus()).isEqualTo("INACTIVE");

        // Restore category, deactivate brand -> effective status becomes INACTIVE
        category.setStatus(CategoryStatus.ACTIVE);
        categoryRepository.save(category);
        Brand brand = brandRepository.findById(product.getBrandId()).orElseThrow();
        brand.setStatus(BrandStatus.INACTIVE);
        brandRepository.save(brand);
        InternalSkuResponse resBrandInactive = skuService.getInternalSku(sku.getId());
        assertThat(resBrandInactive.getStatus()).isEqualTo("INACTIVE");

        // Restore brand, deactivate SKU directly -> effective status becomes INACTIVE
        brand.setStatus(BrandStatus.ACTIVE);
        brandRepository.save(brand);
        skuService.updateSkuStatus(product.getId(), sku.getId(), SkuStatus.INACTIVE, "admin-1");
        InternalSkuResponse resSkuInactive = skuService.getInternalSku(sku.getId());
        assertThat(resSkuInactive.getStatus()).isEqualTo("INACTIVE");
    }
}
