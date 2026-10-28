package com.example.catalogservice.service;

import com.example.catalogservice.dto.req.UpdateBrandRequest;
import com.example.catalogservice.dto.req.UpdateCategoryRequest;
import com.example.catalogservice.entity.Brand;
import com.example.catalogservice.entity.BrandStatus;
import com.example.catalogservice.entity.Category;
import com.example.catalogservice.entity.CategoryStatus;
import com.example.catalogservice.entity.Product;
import com.example.catalogservice.entity.ProductStatus;
import com.example.catalogservice.repository.BrandRepository;
import com.example.catalogservice.repository.CategoryRepository;
import com.example.catalogservice.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class FanOutCascadeWarningTests {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private BrandRepository brandRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private OutboxService outboxService;

    @Test
    @DisplayName("Positive: Category fan-out cascade logs WARN when reaching maxBatches with full batch")
    void testCategoryFanOut_ReachingMaxBatches_LogsWarning(CapturedOutput output) {
        CategoryService categoryService = new CategoryService(categoryRepository, productRepository, auditLogService, outboxService);

        UUID catId = UUID.randomUUID();
        Category existingCategory = Category.builder()
                .id(catId)
                .name("Old Category")
                .slug("old-category")
                .status(CategoryStatus.ACTIVE)
                .build();

        when(categoryRepository.findById(catId)).thenReturn(Optional.of(existingCategory));
        when(categoryRepository.existsBySlugAndIdNot("new-category", catId)).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Return 100 products for all 10 pages (0 to 9)
        List<Product> fullBatch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            fullBatch.add(Product.builder().id(UUID.randomUUID()).name("P" + i).status(ProductStatus.ACTIVE).build());
        }

        when(productRepository.findByCategoryIdAndDeletedAtIsNullOrderByIdAsc(eq(catId), any(PageRequest.class)))
                .thenReturn(fullBatch);

        UpdateCategoryRequest req = UpdateCategoryRequest.builder()
                .name("New Category")
                .slug("new-category")
                .build();

        categoryService.updateCategory(catId, req, "admin-1");

        verify(outboxService, times(1000)).recordProductEvent(any(UUID.class), eq("UPDATED"), anyString(), anyString(), anyLong());
        assertThat(output.getOut())
                .contains("Fan-out cascade event threshold reached (maxBatches=10, 1000 products emitted) for categoryId: " + catId)
                .contains("Please run a Reindex Job");
    }

    @Test
    @DisplayName("Positive: Brand fan-out cascade logs WARN when reaching maxBatches with full batch")
    void testBrandFanOut_ReachingMaxBatches_LogsWarning(CapturedOutput output) {
        BrandService brandService = new BrandService(brandRepository, productRepository, auditLogService, outboxService);

        UUID brandId = UUID.randomUUID();
        Brand existingBrand = Brand.builder()
                .id(brandId)
                .name("Old Brand")
                .slug("old-brand")
                .status(BrandStatus.ACTIVE)
                .build();

        when(brandRepository.findById(brandId)).thenReturn(Optional.of(existingBrand));
        when(brandRepository.save(any(Brand.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Return 100 products for all 10 pages (0 to 9)
        List<Product> fullBatch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            fullBatch.add(Product.builder().id(UUID.randomUUID()).name("BrandProd" + i).status(ProductStatus.ACTIVE).build());
        }

        when(productRepository.findByBrandIdAndDeletedAtIsNullOrderByIdAsc(eq(brandId), any(PageRequest.class)))
                .thenReturn(fullBatch);

        // Update brand status from ACTIVE to INACTIVE to trigger emitProductEventsForBrand
        brandService.updateBrandStatus(brandId, BrandStatus.INACTIVE, "admin-1");

        verify(outboxService, times(1000)).recordProductEvent(any(UUID.class), eq("UPDATED"), anyString(), anyString(), anyLong());
        assertThat(output.getOut())
                .contains("Fan-out cascade event threshold reached (maxBatches=10, 1000 products emitted) for brandId: " + brandId)
                .contains("Please run a Reindex Job");
    }
}
