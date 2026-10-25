package com.example.catalogservice.category;

import com.ecommerce.common.exception.ConflictException;
import com.example.catalogservice.dto.req.UpdateCategoryRequest;
import com.example.catalogservice.entity.Category;
import com.example.catalogservice.entity.CategoryStatus;
import com.example.catalogservice.repository.CategoryRepository;
import com.example.catalogservice.service.CategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "management.health.elasticsearch.enabled=false"
})
class ConcurrentReparentingCycleTests {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CategoryService categoryService;

    @AfterEach
    void tearDown() {
        categoryRepository.deleteAll();
    }

    @Test
    @DisplayName("Concurrency: Concurrent mutual reparenting (A->B and B->A) never creates a cycle")
    void testConcurrentMutualReparenting_NoCycleCreated() throws Exception {
        Category a = categoryRepository.save(Category.builder()
                .name("Cat A")
                .slug("cat-a")
                .status(CategoryStatus.ACTIVE)
                .build());

        Category b = categoryRepository.save(Category.builder()
                .name("Cat B")
                .slug("cat-b")
                .status(CategoryStatus.ACTIVE)
                .build());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        Callable<Void> task1 = () -> {
            barrier.await();
            try {
                categoryService.updateCategory(a.getId(),
                        UpdateCategoryRequest.builder().name("Cat A").slug("cat-a").parentId(b.getId()).build(),
                        "admin-1");
                successCount.incrementAndGet();
            } catch (ConflictException e) {
                conflictCount.incrementAndGet();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            barrier.await();
            try {
                categoryService.updateCategory(b.getId(),
                        UpdateCategoryRequest.builder().name("Cat B").slug("cat-b").parentId(a.getId()).build(),
                        "admin-2");
                successCount.incrementAndGet();
            } catch (ConflictException e) {
                conflictCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task1);
        Future<Void> f2 = executor.submit(task2);

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // One of the reparents must succeed and the other must be rejected with ConflictException (or fail),
        // but both cannot succeed because that would create a cycle (A->B and B->A).
        assertThat(conflictCount.get()).isGreaterThanOrEqualTo(1);

        Category refreshedA = categoryRepository.findById(a.getId()).orElseThrow();
        Category refreshedB = categoryRepository.findById(b.getId()).orElseThrow();

        // Verify no cycle exists: they cannot be each other's parent
        boolean isCycle = Objects.equals(refreshedA.getParentId(), b.getId()) &&
                          Objects.equals(refreshedB.getParentId(), a.getId());
        assertThat(isCycle).isFalse();
    }
}
