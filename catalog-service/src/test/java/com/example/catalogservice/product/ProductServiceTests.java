package com.example.catalogservice.product;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.catalogservice.dto.req.CreateProductRequest;
import com.example.catalogservice.dto.req.CreateSkuRequest;
import com.example.catalogservice.dto.req.UpdateProductRequest;
import com.example.catalogservice.dto.resp.ProductResponse;
import com.example.catalogservice.dto.resp.SkuResponse;
import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.*;
import com.example.catalogservice.service.ProductService;
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
class ProductServiceTests {

    @Autowired
    private ProductService productService;

    @Autowired
    private SkuService skuService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SkuRepository skuRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private CatalogAuditLogRepository auditLogRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.catalogservice.elasticsearch.repository.ProductSkuRepository productSkuRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private org.springframework.data.elasticsearch.core.ElasticsearchOperations elasticsearchOperations;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.catalogservice.elasticsearch.service.ElasticsearchIndexService elasticsearchIndexService;

    private Category activeCategory;
    private Brand activeBrand;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        auditLogRepository.deleteAll();
        skuRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        brandRepository.deleteAll();

        activeCategory = categoryRepository.save(Category.builder()
                .name("Computers")
                .slug("computers")
                .status(CategoryStatus.ACTIVE)
                .build());

        activeBrand = brandRepository.save(Brand.builder()
                .name("Dell")
                .slug("dell")
                .status(BrandStatus.ACTIVE)
                .build());
    }

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
        auditLogRepository.deleteAll();
        skuRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        brandRepository.deleteAll();
    }

    @Test
    @DisplayName("Positive: Create product in DRAFT status records audit log and outbox event")
    void testCreateProduct_Success() {
        CreateProductRequest req = CreateProductRequest.builder()
                .name("XPS 15")
                .description("Dell XPS 15 laptop")
                .metadata("{\"processor\": \"Intel i7\"}")
                .categoryId(activeCategory.getId())
                .brandId(activeBrand.getId())
                .build();

        ProductResponse response = productService.createProduct(req, "admin-1");

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(ProductStatus.DRAFT);
        assertThat(response.getCategoryName()).isEqualTo("Computers");
        assertThat(response.getBrandName()).isEqualTo("Dell");

        // Verify outbox event recorded
        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).hasSize(1);
        OutboxEvent outboxEvent = outboxEvents.get(0);
        assertThat(outboxEvent.getAggregateType()).isEqualTo("PRODUCT");
        assertThat(outboxEvent.getAggregateId()).isEqualTo(response.getId().toString());
        assertThat(outboxEvent.getEventType()).isEqualTo("catalog.product.changed");

        // Verify audit log recorded
        List<CatalogAuditLog> auditLogs = auditLogRepository.findAll();
        assertThat(auditLogs).hasSize(1);
        assertThat(auditLogs.get(0).getAction()).isEqualTo("CREATE_PRODUCT");
        assertThat(auditLogs.get(0).getEntityId()).isEqualTo(response.getId().toString());
    }

    @Test
    @DisplayName("Negative: Create product with INACTIVE category throws BusinessException (422)")
    void testCreateProduct_InactiveCategory_ThrowsBusinessException() {
        Category inactiveCategory = categoryRepository.save(Category.builder()
                .name("Old Category")
                .slug("old-category")
                .status(CategoryStatus.INACTIVE)
                .build());

        CreateProductRequest req = CreateProductRequest.builder()
                .name("XPS 15")
                .categoryId(inactiveCategory.getId())
                .brandId(activeBrand.getId())
                .build();

        assertThatThrownBy(() -> productService.createProduct(req, "admin-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
    }

    @Test
    @DisplayName("Negative: Create product with non-existent brand throws ResourceNotFoundException (404)")
    void testCreateProduct_BrandNotFound_ThrowsResourceNotFound() {
        CreateProductRequest req = CreateProductRequest.builder()
                .name("XPS 15")
                .categoryId(activeCategory.getId())
                .brandId(UUID.randomUUID())
                .build();

        assertThatThrownBy(() -> productService.createProduct(req, "admin-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Negative: Activate product without at least one ACTIVE SKU throws BusinessException (422)")
    void testActivateProduct_NoActiveSku_ThrowsBusinessException() {
        CreateProductRequest req = CreateProductRequest.builder()
                .name("XPS 15")
                .categoryId(activeCategory.getId())
                .brandId(activeBrand.getId())
                .build();

        ProductResponse product = productService.createProduct(req, "admin-1");

        // Product has no SKUs yet
        assertThatThrownBy(() -> productService.updateProductStatus(product.getId(), ProductStatus.ACTIVE, "admin-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ACTIVE SKU");
    }

    @Test
    @DisplayName("Positive: Activate product with an ACTIVE SKU succeeds")
    void testActivateProduct_WithActiveSku_Succeeds() {
        CreateProductRequest req = CreateProductRequest.builder()
                .name("XPS 15")
                .categoryId(activeCategory.getId())
                .brandId(activeBrand.getId())
                .build();

        ProductResponse product = productService.createProduct(req, "admin-1");

        // Add SKU and activate it
        SkuResponse sku = skuService.createSku(product.getId(), CreateSkuRequest.builder()
                .skuCode("DELL-XPS15-01")
                .name("Dell XPS 15 16GB")
                .price(new BigDecimal("35000000"))
                .build(), "admin-1");
        skuService.updateSkuStatus(product.getId(), sku.getId(), SkuStatus.ACTIVE, "admin-1");

        // Now activate product
        ProductResponse activated = productService.updateProductStatus(product.getId(), ProductStatus.ACTIVE, "admin-1");
        assertThat(activated.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    @DisplayName("Positive: Delete product soft-deletes product and all its SKUs")
    void testDeleteProduct_CascadesSoftDeleteToSkus() {
        CreateProductRequest req = CreateProductRequest.builder()
                .name("XPS 15")
                .categoryId(activeCategory.getId())
                .brandId(activeBrand.getId())
                .build();

        ProductResponse product = productService.createProduct(req, "admin-1");
        SkuResponse sku = skuService.createSku(product.getId(), CreateSkuRequest.builder()
                .skuCode("DELL-XPS15-01")
                .name("Dell XPS 15 16GB")
                .price(new BigDecimal("35000000"))
                .build(), "admin-1");

        productService.deleteProduct(product.getId(), "admin-1");

        assertThat(productRepository.findByIdAndDeletedAtIsNull(product.getId())).isEmpty();
        assertThat(skuRepository.findByIdAndDeletedAtIsNull(sku.getId())).isEmpty();

        // Verify outbox contains DELETED action
        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents.stream().anyMatch(e -> e.getPayload().contains("\"action\":\"DELETED\""))).isTrue();
    }

    @Test
    @DisplayName("Positive: getPublicProductDetail returns active product and only active SKUs")
    void testGetPublicProductDetail_ReturnsActiveOnly() {
        CreateProductRequest req = CreateProductRequest.builder()
                .name("MacBook Air M2")
                .categoryId(activeCategory.getId())
                .brandId(activeBrand.getId())
                .build();

        ProductResponse product = productService.createProduct(req, "admin-1");
        SkuResponse activeSku = skuService.createSku(product.getId(), CreateSkuRequest.builder()
                .skuCode("MBA-M2-256")
                .name("MacBook Air M2 256GB")
                .price(new BigDecimal("26000000"))
                .build(), "admin-1");
        skuService.updateSkuStatus(product.getId(), activeSku.getId(), SkuStatus.ACTIVE, "admin-1");

        SkuResponse draftSku = skuService.createSku(product.getId(), CreateSkuRequest.builder()
                .skuCode("MBA-M2-512")
                .name("MacBook Air M2 512GB")
                .price(new BigDecimal("32000000"))
                .build(), "admin-1");
        skuService.updateSkuStatus(product.getId(), draftSku.getId(), SkuStatus.INACTIVE, "admin-1");

        productService.updateProductStatus(product.getId(), ProductStatus.ACTIVE, "admin-1");

        com.example.catalogservice.dto.resp.PublicProductDetailResponse detail =
                productService.getPublicProductDetail(product.getId());

        assertThat(detail.getId()).isEqualTo(product.getId());
        assertThat(detail.getName()).isEqualTo("MacBook Air M2");
        assertThat(detail.getSkus()).hasSize(1);
        assertThat(detail.getSkus().get(0).getSkuCode()).isEqualTo("MBA-M2-256");
    }

    @Test
    @DisplayName("Negative: getPublicProductDetail throws ResourceNotFoundException if product is DRAFT")
    void testGetPublicProductDetail_DraftThrowsNotFound() {
        CreateProductRequest req = CreateProductRequest.builder()
                .name("Draft Product")
                .categoryId(activeCategory.getId())
                .brandId(activeBrand.getId())
                .build();
        ProductResponse product = productService.createProduct(req, "admin-1");

        assertThatThrownBy(() -> productService.getPublicProductDetail(product.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Negative: getPublicProductDetail throws ResourceNotFoundException if category is INACTIVE")
    void testGetPublicProductDetail_InactiveCategoryThrowsNotFound() {
        Category inactiveCategory = categoryRepository.save(Category.builder()
                .name("Inactive Category")
                .slug("inactive-cat")
                .status(CategoryStatus.INACTIVE)
                .build());

        CreateProductRequest req = CreateProductRequest.builder()
                .name("Product in Inactive Cat")
                .categoryId(activeCategory.getId())
                .brandId(activeBrand.getId())
                .build();
        ProductResponse product = productService.createProduct(req, "admin-1");
        SkuResponse sku = skuService.createSku(product.getId(), CreateSkuRequest.builder()
                .skuCode("INACT-CAT-SKU")
                .name("SKU")
                .price(new BigDecimal("1000000"))
                .build(), "admin-1");
        skuService.updateSkuStatus(product.getId(), sku.getId(), SkuStatus.ACTIVE, "admin-1");
        productService.updateProductStatus(product.getId(), ProductStatus.ACTIVE, "admin-1");

        // Now move category to inactive directly in DB
        Product p = productRepository.findById(product.getId()).get();
        p.setCategoryId(inactiveCategory.getId());
        productRepository.save(p);

        assertThatThrownBy(() -> productService.getPublicProductDetail(product.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
