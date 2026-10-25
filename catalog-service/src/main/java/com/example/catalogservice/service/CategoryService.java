package com.example.catalogservice.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.catalogservice.dto.req.CreateCategoryRequest;
import com.example.catalogservice.dto.req.UpdateCategoryRequest;
import com.example.catalogservice.dto.resp.CategoryResponse;
import com.example.catalogservice.dto.resp.CategoryTreeNode;
import com.example.catalogservice.entity.Category;
import com.example.catalogservice.entity.CategoryStatus;
import com.example.catalogservice.entity.ProductStatus;
import com.example.catalogservice.repository.CategoryRepository;
import com.example.catalogservice.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final AuditLogService auditLogService;
    private final OutboxService outboxService;

    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest req, String actorId) {
        String cleanSlug = req.getSlug().trim().toLowerCase();
        if (categoryRepository.existsBySlug(cleanSlug)) {
            throw new ConflictException("Category slug already exists: " + cleanSlug);
        }

        if (req.getParentId() != null) {
            Category parent = categoryRepository.findById(req.getParentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Parent category not found with id: " + req.getParentId()));
            if (parent.getStatus() != CategoryStatus.ACTIVE) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Parent category must be ACTIVE");
            }
        }

        Category category = Category.builder()
                .name(req.getName().trim())
                .slug(cleanSlug)
                .description(req.getDescription())
                .parentId(req.getParentId())
                .status(CategoryStatus.ACTIVE)
                .displayOrder(req.getDisplayOrder())
                .build();

        Category saved = categoryRepository.save(category);
        auditLogService.record(actorId, "USER", "CREATE_CATEGORY", "CATEGORY", saved.getId().toString(), null, toResponse(saved));
        return toResponse(saved);
    }

    @Transactional
    public CategoryResponse updateCategory(UUID id, UpdateCategoryRequest req, String actorId) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));

        CategoryResponse beforeState = toResponse(category);
        String cleanSlug = req.getSlug().trim().toLowerCase();

        if (categoryRepository.existsBySlugAndIdNot(cleanSlug, id)) {
            throw new ConflictException("Category slug already exists: " + cleanSlug);
        }

        if (req.getParentId() != null) {
            if (req.getParentId().equals(id)) {
                throw new ConflictException("Category cannot be its own parent");
            }

            // Acquire pessimistic locks in deterministic order to prevent concurrent race condition cycles and deadlocks
            UUID firstLock = id.compareTo(req.getParentId()) < 0 ? id : req.getParentId();
            UUID secondLock = id.compareTo(req.getParentId()) < 0 ? req.getParentId() : id;
            categoryRepository.findByIdForUpdate(firstLock);
            categoryRepository.findByIdForUpdate(secondLock);

            Category parent = categoryRepository.findById(req.getParentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Parent category not found with id: " + req.getParentId()));

            if (parent.getStatus() != CategoryStatus.ACTIVE) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Parent category must be ACTIVE");
            }

            // Cycle prevention check: traverse up ancestor chain of parent
            UUID currentAncestorId = parent.getParentId();
            Set<UUID> visited = new HashSet<>();
            visited.add(parent.getId());

            while (currentAncestorId != null) {
                if (currentAncestorId.equals(id)) {
                    throw new ConflictException("Reparenting would introduce a cycle in category tree: category " + id + " is ancestor of " + req.getParentId());
                }
                if (!visited.add(currentAncestorId)) {
                    break; // prevent infinite loop on corrupted existing data
                }
                currentAncestorId = categoryRepository.findById(currentAncestorId)
                        .map(Category::getParentId)
                        .orElse(null);
            }
        }

        category.setName(req.getName().trim());
        category.setSlug(cleanSlug);
        category.setDescription(req.getDescription());
        category.setParentId(req.getParentId());
        category.setDisplayOrder(req.getDisplayOrder());

        Category updated = categoryRepository.save(category);
        auditLogService.record(actorId, "USER", "UPDATE_CATEGORY", "CATEGORY", updated.getId().toString(), beforeState, toResponse(updated));

        if (!beforeState.getName().equals(updated.getName())) {
            emitProductEventsForCategory(updated.getId());
        }

        return toResponse(updated);
    }

    @Transactional
    public CategoryResponse updateCategoryStatus(UUID id, CategoryStatus newStatus, String actorId) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));

        CategoryResponse beforeState = toResponse(category);

        if (category.getStatus() == CategoryStatus.ACTIVE && newStatus == CategoryStatus.INACTIVE) {
            if (productRepository.existsByCategoryIdAndStatusAndDeletedAtIsNull(id, ProductStatus.ACTIVE)) {
                throw new ConflictException("Cannot deactivate category with active products");
            }
        }

        category.setStatus(newStatus);
        Category updated = categoryRepository.save(category);
        auditLogService.record(actorId, "USER", "UPDATE_CATEGORY_STATUS", "CATEGORY", updated.getId().toString(), beforeState, toResponse(updated));

        if (beforeState.getStatus() != updated.getStatus()) {
            emitProductEventsForCategory(updated.getId());
        }

        return toResponse(updated);
    }

    private void emitProductEventsForCategory(UUID categoryId) {
        int pageSize = 100;
        int maxBatches = 10; // Bounded fan-out up to 1,000 products per transaction
        long now = System.currentTimeMillis();
        for (int page = 0; page < maxBatches; page++) {
            List<com.example.catalogservice.entity.Product> products =
                    productRepository.findByCategoryIdAndDeletedAtIsNullOrderByIdAsc(categoryId, org.springframework.data.domain.PageRequest.of(page, pageSize));
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
    public void deleteCategory(UUID id, String actorId) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));

        if (categoryRepository.existsByParentId(id)) {
            throw new ConflictException("Cannot delete category with subcategories");
        }

        if (productRepository.existsByCategoryIdAndDeletedAtIsNull(id)) {
            throw new ConflictException("Cannot delete category with associated products");
        }

        CategoryResponse beforeState = toResponse(category);
        categoryRepository.delete(category);
        auditLogService.record(actorId, "USER", "DELETE_CATEGORY", "CATEGORY", id.toString(), beforeState, null);
    }

    @Transactional(readOnly = true)
    public CategoryResponse getCategoryById(UUID id) {
        return categoryRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAllByOrderByDisplayOrderAscNameAscIdAsc().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CategoryTreeNode> getPublicCategoryTree() {
        List<Category> activeCategories = categoryRepository.findByStatusOrderByDisplayOrderAscNameAscIdAsc(CategoryStatus.ACTIVE);
        Map<UUID, List<Category>> childrenByParent = new HashMap<>();

        for (Category cat : activeCategories) {
            if (cat.getParentId() != null) {
                childrenByParent.computeIfAbsent(cat.getParentId(), k -> new ArrayList<>()).add(cat);
            }
        }

        List<CategoryTreeNode> rootNodes = new ArrayList<>();
        for (Category cat : activeCategories) {
            if (cat.getParentId() == null) {
                rootNodes.add(buildTreeNode(cat, childrenByParent));
            }
        }
        return rootNodes;
    }

    private CategoryTreeNode buildTreeNode(Category category, Map<UUID, List<Category>> childrenByParent) {
        List<Category> children = childrenByParent.getOrDefault(category.getId(), Collections.emptyList());
        List<CategoryTreeNode> childNodes = children.stream()
                .map(c -> buildTreeNode(c, childrenByParent))
                .collect(Collectors.toList());

        return CategoryTreeNode.builder()
                .id(category.getId())
                .name(category.getName())
                .slug(category.getSlug())
                .description(category.getDescription())
                .displayOrder(category.getDisplayOrder())
                .children(childNodes)
                .build();
    }

    private CategoryResponse toResponse(Category category) {
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .slug(category.getSlug())
                .description(category.getDescription())
                .parentId(category.getParentId())
                .status(category.getStatus())
                .displayOrder(category.getDisplayOrder())
                .createdAt(category.getCreatedAt())
                .updatedAt(category.getUpdatedAt())
                .build();
    }
}
