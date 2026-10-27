package com.example.catalogservice.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@org.junit.jupiter.api.condition.EnabledIf("isPostgresAvailable")
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/catalog_db",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "security.internal-service-keys.order-service=order-service-secret-key-32chars"
})
class JpaSchemaTests {

    static boolean isPostgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM product_images");
        jdbcTemplate.update("DELETE FROM skus");
        jdbcTemplate.update("DELETE FROM sku_stock_snapshots");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM categories");
        jdbcTemplate.update("DELETE FROM brands");
    }

    @Test
    @DisplayName("Positive: Hibernate DDL creates all core catalog tables successfully")
    void testTablesExist() {
        Integer categoryCount = jdbcTemplate.queryForObject("SELECT count(*) FROM categories", Integer.class);
        assertThat(categoryCount).isNotNull();

        Integer brandCount = jdbcTemplate.queryForObject("SELECT count(*) FROM brands", Integer.class);
        assertThat(brandCount).isNotNull();

        Integer productCount = jdbcTemplate.queryForObject("SELECT count(*) FROM products", Integer.class);
        assertThat(productCount).isNotNull();

        Integer skuCount = jdbcTemplate.queryForObject("SELECT count(*) FROM skus", Integer.class);
        assertThat(skuCount).isNotNull();

        Integer stockSnapshotCount = jdbcTemplate.queryForObject("SELECT count(*) FROM sku_stock_snapshots", Integer.class);
        assertThat(stockSnapshotCount).isNotNull();

        Integer outboxCount = jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
        assertThat(outboxCount).isNotNull();

        Integer processedCount = jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Integer.class);
        assertThat(processedCount).isNotNull();

        Integer reindexJobCount = jdbcTemplate.queryForObject("SELECT count(*) FROM catalog_reindex_jobs", Integer.class);
        assertThat(reindexJobCount).isNotNull();
    }

    @Test
    @DisplayName("Positive: Insert valid category, brand, product and SKU within Decimal bounds")
    void testValidInsertWithinDecimalBounds() {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO categories (id, name, slug, status, display_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                categoryId, "Electronics", "electronics-" + categoryId, "ACTIVE", 1
        );

        UUID brandId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO brands (id, name, slug, status, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                brandId, "Apple", "apple-" + brandId, "ACTIVE"
        );

        UUID productId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO products (id, name, category_id, brand_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                productId, "iPhone 16 Pro", categoryId, brandId, "ACTIVE"
        );

        UUID skuId = UUID.randomUUID();
        BigDecimal validPrice = new BigDecimal("29999999.9999");
        jdbcTemplate.update(
                "INSERT INTO skus (id, product_id, sku_code, name, price, currency, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())",
                skuId, productId, "SKU-IPHONE16P-256", "iPhone 16 Pro 256GB", validPrice, "VND", "ACTIVE"
        );

        BigDecimal storedPrice = jdbcTemplate.queryForObject(
                "SELECT price FROM skus WHERE id = ?", BigDecimal.class, skuId
        );
        assertThat(storedPrice).isEqualByComparingTo(validPrice);
    }

    @Test
    @DisplayName("Negative: Duplicate category slug violates unique constraint")
    void testDuplicateCategorySlug_Rejected() {
        String slug = "duplicate-category-slug-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO categories (id, name, slug, status, display_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), "Cat 1", slug, "ACTIVE", 0
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO categories (id, name, slug, status, display_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), "Cat 2", slug, "ACTIVE", 0
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Negative: Duplicate SKU code violates unique constraint")
    void testDuplicateSkuCode_Rejected() {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO categories (id, name, slug, status, display_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                categoryId, "C", "c-" + categoryId, "ACTIVE", 0);
        UUID brandId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO brands (id, name, slug, status, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                brandId, "B", "b-" + brandId, "ACTIVE");
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO products (id, name, category_id, brand_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                productId, "P", categoryId, brandId, "ACTIVE");

        String skuCode = "SKU-DUP-CODE-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO skus (id, product_id, sku_code, name, price, currency, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), productId, skuCode, "SKU 1", new BigDecimal("10000.0000"), "VND", "ACTIVE"
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO skus (id, product_id, sku_code, name, price, currency, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), productId, skuCode, "SKU 2", new BigDecimal("20000.0000"), "VND", "ACTIVE"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
