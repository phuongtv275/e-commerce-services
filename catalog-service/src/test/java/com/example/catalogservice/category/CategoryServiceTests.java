package com.example.catalogservice.category;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.CreateCategoryRequest;
import com.example.catalogservice.dto.req.UpdateCategoryRequest;
import com.example.catalogservice.dto.resp.CategoryResponse;
import com.example.catalogservice.dto.resp.CategoryTreeNode;
import com.example.catalogservice.entity.Category;
import com.example.catalogservice.entity.CategoryStatus;
import com.example.catalogservice.repository.CategoryRepository;
import com.example.catalogservice.repository.ProductRepository;
import com.example.catalogservice.service.AuditLogService;
import com.example.catalogservice.service.CategoryService;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.catalogservice.entity.Brand;
import com.example.catalogservice.entity.BrandStatus;
import com.example.catalogservice.entity.Product;
import com.example.catalogservice.entity.ProductStatus;
import com.example.catalogservice.repository.BrandRepository;
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
class CategoryServiceTests {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("Positive: Create root category successfully")
    void testCreateRootCategory_Success() {
        CreateCategoryRequest req = CreateCategoryRequest.builder()
                .name("Electronics")
                .slug("electronics")
                .description("All electronic items")
                .displayOrder(1)
                .build();

        CategoryResponse resp = categoryService.createCategory(req, "admin-1");
        assertThat(resp.getId()).isNotNull();
        assertThat(resp.getName()).isEqualTo("Electronics");
        assertThat(resp.getSlug()).isEqualTo("electronics");
        assertThat(resp.getStatus()).isEqualTo(CategoryStatus.ACTIVE);
        assertThat(resp.getParentId()).isNull();
    }

    @Test
    @DisplayName("Positive: Create subcategory under active parent")
    void testCreateSubCategory_Success() {
        Category parent = categoryRepository.save(Category.builder()
                .name("Computers")
                .slug("computers")
                .status(CategoryStatus.ACTIVE)
                .displayOrder(1)
                .build());

        CreateCategoryRequest req = CreateCategoryRequest.builder()
                .name("Laptops")
                .slug("laptops")
                .parentId(parent.getId())
                .displayOrder(1)
                .build();

        CategoryResponse resp = categoryService.createCategory(req, "admin-1");
        assertThat(resp.getParentId()).isEqualTo(parent.getId());
    }

    @Test
    @DisplayName("Negative: Create category with duplicate slug throws ConflictException (409)")
    void testCreateCategory_DuplicateSlug_ThrowsConflict() {
        categoryRepository.save(Category.builder()
                .name("Books")
                .slug("books")
                .status(CategoryStatus.ACTIVE)
                .displayOrder(1)
                .build());

        CreateCategoryRequest req = CreateCategoryRequest.builder()
                .name("Books 2")
                .slug("books")
                .displayOrder(2)
                .build();

        assertThatThrownBy(() -> categoryService.createCategory(req, "admin-1"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Negative: Create subcategory with non-existent parent throws ResourceNotFoundException (404)")
    void testCreateSubCategory_NonExistentParent_ThrowsNotFound() {
        CreateCategoryRequest req = CreateCategoryRequest.builder()
                .name("Tablets")
                .slug("tablets")
                .parentId(UUID.randomUUID())
                .displayOrder(1)
                .build();

        assertThatThrownBy(() -> categoryService.createCategory(req, "admin-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Negative: Create subcategory under INACTIVE parent throws BusinessException (422)")
    void testCreateSubCategory_InactiveParent_ThrowsBusinessException() {
        Category parent = categoryRepository.save(Category.builder()
                .name("Old Category")
                .slug("old-category")
                .status(CategoryStatus.INACTIVE)
                .displayOrder(1)
                .build());

        CreateCategoryRequest req = CreateCategoryRequest.builder()
                .name("Sub Old")
                .slug("sub-old")
                .parentId(parent.getId())
                .displayOrder(1)
                .build();

        assertThatThrownBy(() -> categoryService.createCategory(req, "admin-1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Negative: Updating category parent to self throws ConflictException (409)")
    void testReparentToSelf_ThrowsConflict() {
        Category cat = categoryRepository.save(Category.builder()
                .name("Audio")
                .slug("audio")
                .status(CategoryStatus.ACTIVE)
                .displayOrder(1)
                .build());

        UpdateCategoryRequest req = UpdateCategoryRequest.builder()
                .name("Audio Updated")
                .slug("audio")
                .parentId(cat.getId())
                .displayOrder(1)
                .build();

        assertThatThrownBy(() -> categoryService.updateCategory(cat.getId(), req, "admin-1"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Negative: Reparenting that causes cycle (A -> B -> C -> A) throws ConflictException (409)")
    void testReparentCycle_ThrowsConflict() {
        Category a = categoryRepository.save(Category.builder().name("A").slug("a").status(CategoryStatus.ACTIVE).build());
        Category b = categoryRepository.save(Category.builder().name("B").slug("b").parentId(a.getId()).status(CategoryStatus.ACTIVE).build());
        Category c = categoryRepository.save(Category.builder().name("C").slug("c").parentId(b.getId()).status(CategoryStatus.ACTIVE).build());

        // Attempt to make A a child of C (creating A -> B -> C -> A cycle)
        UpdateCategoryRequest req = UpdateCategoryRequest.builder()
                .name("A")
                .slug("a")
                .parentId(c.getId())
                .build();

        assertThatThrownBy(() -> categoryService.updateCategory(a.getId(), req, "admin-1"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("Positive: Build public category tree returns only ACTIVE categories in sorted hierarchy")
    void testGetPublicCategoryTree() {
        Category root1 = categoryRepository.save(Category.builder().name("B Root").slug("b-root").status(CategoryStatus.ACTIVE).displayOrder(2).build());
        Category root2 = categoryRepository.save(Category.builder().name("A Root").slug("a-root").status(CategoryStatus.ACTIVE).displayOrder(1).build());
        Category inactiveRoot = categoryRepository.save(Category.builder().name("Inactive Root").slug("inactive-root").status(CategoryStatus.INACTIVE).displayOrder(0).build());

        Category child2 = categoryRepository.save(Category.builder().name("Child 2").slug("child-2").parentId(root2.getId()).status(CategoryStatus.ACTIVE).displayOrder(2).build());
        Category child1 = categoryRepository.save(Category.builder().name("Child 1").slug("child-1").parentId(root2.getId()).status(CategoryStatus.ACTIVE).displayOrder(1).build());
        Category inactiveChild = categoryRepository.save(Category.builder().name("Inactive Child").slug("inactive-child").parentId(root2.getId()).status(CategoryStatus.INACTIVE).displayOrder(0).build());

        List<CategoryTreeNode> tree = categoryService.getPublicCategoryTree();
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).getName()).isEqualTo("A Root");
        assertThat(tree.get(1).getName()).isEqualTo("B Root");

        assertThat(tree.get(0).getChildren()).hasSize(2);
        assertThat(tree.get(0).getChildren().get(0).getName()).isEqualTo("Child 1");
        assertThat(tree.get(0).getChildren().get(1).getName()).isEqualTo("Child 2");
    }

    @Test
    @DisplayName("Negative: Delete category with children throws ConflictException (409)")
    void testDeleteCategory_WithChildren_ThrowsConflict() {
        Category parent = categoryRepository.save(Category.builder().name("Parent").slug("parent").status(CategoryStatus.ACTIVE).build());
        categoryRepository.save(Category.builder().name("Child").slug("child").parentId(parent.getId()).status(CategoryStatus.ACTIVE).build());

        assertThatThrownBy(() -> categoryService.deleteCategory(parent.getId(), "admin-1"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Positive: Updating category name emits bounded Outbox fan-out product events")
    void testUpdateCategory_NameChangeEmitsOutboxFanOut() {
        Category cat = categoryRepository.save(Category.builder()
                .name("Old Category")
                .slug("old-cat")
                .status(CategoryStatus.ACTIVE)
                .build());

        Brand brand = brandRepository.save(Brand.builder()
                .name("Test Brand")
                .slug("test-brand")
                .status(BrandStatus.ACTIVE)
                .build());

        Product p1 = productRepository.save(Product.builder()
                .name("Product 1")
                .categoryId(cat.getId())
                .brandId(brand.getId())
                .status(ProductStatus.ACTIVE)
                .build());

        Product p2 = productRepository.save(Product.builder()
                .name("Product 2")
                .categoryId(cat.getId())
                .brandId(brand.getId())
                .status(ProductStatus.ACTIVE)
                .build());

        long beforeCount = outboxEventRepository.count();

        categoryService.updateCategory(cat.getId(), UpdateCategoryRequest.builder()
                .name("New Category Name")
                .slug("old-cat")
                .build(), "admin-1");

        long afterCount = outboxEventRepository.count();
        assertThat(afterCount - beforeCount).isGreaterThanOrEqualTo(2);

        List<OutboxEvent> events = outboxEventRepository.findAll();
        List<String> aggregateIds = events.stream().map(OutboxEvent::getAggregateId).toList();
        assertThat(aggregateIds).contains(p1.getId().toString(), p2.getId().toString());
    }
}
