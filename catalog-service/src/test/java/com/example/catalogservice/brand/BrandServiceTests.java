package com.example.catalogservice.brand;

import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.CreateBrandRequest;
import com.example.catalogservice.dto.req.UpdateBrandRequest;
import com.example.catalogservice.dto.resp.BrandResponse;
import com.example.catalogservice.entity.Brand;
import com.example.catalogservice.entity.BrandStatus;
import com.example.catalogservice.repository.BrandRepository;
import com.example.catalogservice.service.BrandService;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.CategoryRepository;
import com.example.catalogservice.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "management.health.elasticsearch.enabled=false"
})
class BrandServiceTests {

    @Autowired
    private BrandService brandService;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("Positive: Create brand successfully")
    void testCreateBrand_Success() {
        CreateBrandRequest req = CreateBrandRequest.builder()
                .name("Samsung")
                .slug("samsung")
                .description("South Korean electronics brand")
                .logoUrl("https://example.com/samsung-logo.png")
                .build();

        BrandResponse resp = brandService.createBrand(req, "admin-1");
        assertThat(resp.getId()).isNotNull();
        assertThat(resp.getName()).isEqualTo("Samsung");
        assertThat(resp.getSlug()).isEqualTo("samsung");
        assertThat(resp.getStatus()).isEqualTo(BrandStatus.ACTIVE);
    }

    @Test
    @DisplayName("Negative: Create brand with duplicate slug throws ConflictException (409)")
    void testCreateBrand_DuplicateSlug_ThrowsConflict() {
        brandRepository.save(Brand.builder()
                .name("Sony")
                .slug("sony")
                .status(BrandStatus.ACTIVE)
                .build());

        CreateBrandRequest req = CreateBrandRequest.builder()
                .name("Sony Electronics")
                .slug("sony")
                .build();

        assertThatThrownBy(() -> brandService.createBrand(req, "admin-1"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Positive: Update brand details")
    void testUpdateBrand_Success() {
        Brand brand = brandRepository.save(Brand.builder()
                .name("LG")
                .slug("lg")
                .status(BrandStatus.ACTIVE)
                .build());

        UpdateBrandRequest req = UpdateBrandRequest.builder()
                .name("LG Electronics")
                .slug("lg-electronics")
                .description("Life's Good")
                .logoUrl("https://example.com/lg.png")
                .build();

        BrandResponse resp = brandService.updateBrand(brand.getId(), req, "admin-1");
        assertThat(resp.getName()).isEqualTo("LG Electronics");
        assertThat(resp.getSlug()).isEqualTo("lg-electronics");
    }

    @Test
    @DisplayName("Positive: Public brands list returns only ACTIVE brands ordered by name ASC")
    void testGetPublicBrands() {
        brandRepository.save(Brand.builder().name("Dell").slug("dell").status(BrandStatus.ACTIVE).build());
        brandRepository.save(Brand.builder().name("Apple").slug("apple").status(BrandStatus.ACTIVE).build());
        brandRepository.save(Brand.builder().name("Obsolete").slug("obsolete").status(BrandStatus.INACTIVE).build());

        List<BrandResponse> brands = brandService.getPublicBrands();
        assertThat(brands).hasSize(2);
        assertThat(brands.get(0).getName()).isEqualTo("Apple");
        assertThat(brands.get(1).getName()).isEqualTo("Dell");
    }

    @Test
    @DisplayName("Negative: Get brand by non-existent id throws ResourceNotFoundException (404)")
    void testGetBrandById_NotFound() {
        assertThatThrownBy(() -> brandService.getBrandById(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Positive: Updating brand name emits bounded Outbox fan-out product events")
    void testUpdateBrand_NameChangeEmitsOutboxFanOut() {
        Category cat = categoryRepository.save(Category.builder()
                .name("Brand Test Category")
                .slug("brand-test-cat")
                .status(CategoryStatus.ACTIVE)
                .build());

        Brand brand = brandRepository.save(Brand.builder()
                .name("Initial Brand")
                .slug("initial-brand")
                .status(BrandStatus.ACTIVE)
                .build());

        Product p1 = productRepository.save(Product.builder()
                .name("Brand Product 1")
                .categoryId(cat.getId())
                .brandId(brand.getId())
                .status(ProductStatus.ACTIVE)
                .build());

        Product p2 = productRepository.save(Product.builder()
                .name("Brand Product 2")
                .categoryId(cat.getId())
                .brandId(brand.getId())
                .status(ProductStatus.ACTIVE)
                .build());

        long beforeCount = outboxEventRepository.count();

        brandService.updateBrand(brand.getId(), UpdateBrandRequest.builder()
                .name("Updated Brand Name")
                .slug("initial-brand")
                .build(), "admin-1");

        long afterCount = outboxEventRepository.count();
        assertThat(afterCount - beforeCount).isGreaterThanOrEqualTo(2);

        List<OutboxEvent> events = outboxEventRepository.findAll();
        List<String> aggregateIds = events.stream().map(OutboxEvent::getAggregateId).toList();
        assertThat(aggregateIds).contains(p1.getId().toString(), p2.getId().toString());
    }
}
