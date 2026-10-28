package com.example.catalogservice.config;

import com.example.catalogservice.entity.*;
import com.example.catalogservice.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogDataSeederTests {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private BrandRepository brandRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private SkuRepository skuRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private SkuStockSnapshotRepository skuStockSnapshotRepository;

    @InjectMocks
    private CatalogDataSeeder catalogDataSeeder;

    @Test
    @DisplayName("Positive: Seeds categories, brands, products, skus, and stock snapshots when categoryRepository is empty")
    void testRun_EmptyDatabase_SeedsDataSuccessfully() {
        when(categoryRepository.count()).thenReturn(0L);

        catalogDataSeeder.run(new DefaultApplicationArguments());

        // Verify categories
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Category>> categoryCaptor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(categoryCaptor.capture());
        List<Category> seededCategories = categoryCaptor.getValue();
        assertThat(seededCategories).hasSize(3);
        assertThat(seededCategories).extracting(Category::getName)
                .containsExactlyInAnyOrder("Điện tử", "Thời trang", "Gia dụng");

        // Verify brands
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Brand>> brandCaptor = ArgumentCaptor.forClass(List.class);
        verify(brandRepository).saveAll(brandCaptor.capture());
        List<Brand> seededBrands = brandCaptor.getValue();
        assertThat(seededBrands).hasSize(3);
        assertThat(seededBrands).extracting(Brand::getName)
                .containsExactlyInAnyOrder("Apple", "Samsung", "Sony");

        // Verify products
        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository, times(2)).save(productCaptor.capture());
        List<Product> seededProducts = productCaptor.getAllValues();
        assertThat(seededProducts).hasSize(2);
        assertThat(seededProducts).extracting(Product::getStatus)
                .containsOnly(ProductStatus.ACTIVE);

        // Verify product images
        ArgumentCaptor<ProductImage> imageCaptor = ArgumentCaptor.forClass(ProductImage.class);
        verify(productImageRepository, times(2)).save(imageCaptor.capture());
        List<ProductImage> seededImages = imageCaptor.getAllValues();
        assertThat(seededImages).hasSize(2);
        assertThat(seededImages).allMatch(ProductImage::isPrimary);

        // Verify skus
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Sku>> skuCaptor = ArgumentCaptor.forClass(List.class);
        verify(skuRepository, times(1)).saveAll(skuCaptor.capture());
        verify(skuRepository, times(1)).save(any(Sku.class));
        List<Sku> batchSkus = skuCaptor.getValue();
        assertThat(batchSkus).allMatch(sku -> sku.getStatus() == SkuStatus.ACTIVE);

        // Verify stock snapshots
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkuStockSnapshot>> snapshotCaptor = ArgumentCaptor.forClass(List.class);
        verify(skuStockSnapshotRepository).saveAll(snapshotCaptor.capture());
        List<SkuStockSnapshot> seededSnapshots = snapshotCaptor.getValue();
        assertThat(seededSnapshots).hasSize(3);
        assertThat(seededSnapshots).allMatch(snapshot -> Boolean.TRUE.equals(snapshot.getInStock()) && snapshot.getInventoryVersion() == 1L);
    }

    @Test
    @DisplayName("Negative: Skips seeding when categories already exist in database")
    void testRun_DataAlreadyExists_SkipsSeeding() {
        when(categoryRepository.count()).thenReturn(5L);

        catalogDataSeeder.run(new DefaultApplicationArguments());

        verify(categoryRepository, never()).saveAll(any());
        verify(brandRepository, never()).saveAll(any());
        verify(productRepository, never()).save(any());
        verify(productImageRepository, never()).save(any());
        verify(skuRepository, never()).save(any());
        verify(skuRepository, never()).saveAll(any());
        verify(skuStockSnapshotRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Negative: Database error during category saving throws exception")
    void testRun_DatabaseError_ThrowsException() {
        when(categoryRepository.count()).thenReturn(0L);
        when(categoryRepository.saveAll(any())).thenThrow(new RuntimeException("Database error saving categories"));

        assertThatThrownBy(() -> catalogDataSeeder.run(new DefaultApplicationArguments()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database error saving categories");
    }
}
