package com.example.catalogservice.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@org.junit.jupiter.api.condition.EnabledIf("isPostgresAvailable")
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/catalog_db",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "security.internal-service-keys.order-service=order-service-secret-key-32chars"
})
class FlywaySchemaTests {

    static boolean isPostgresAvailable() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", 5432), 1000);
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
    @DisplayName("Positive: Flyway migrations execute successfully and create core tables")
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
                "INSERT INTO categories (id, name, slug, status, display_order) VALUES (?, ?, ?, ?, ?)",
                categoryId, "Electronics", "electronics-" + categoryId, "ACTIVE", 1
        );

        UUID brandId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO brands (id, name, slug, status) VALUES (?, ?, ?, ?)",
                brandId, "Apple", "apple-" + brandId, "ACTIVE"
        );

        UUID productId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO products (id, name, category_id, brand_id, status) VALUES (?, ?, ?, ?, ?)",
                productId, "iPhone 16 Pro", categoryId, brandId, "ACTIVE"
        );

        UUID skuId = UUID.randomUUID();
        BigDecimal validPrice = new BigDecimal("29999999.9999");
        jdbcTemplate.update(
                "INSERT INTO skus (id, product_id, sku_code, name, price, currency, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                skuId, productId, "SKU-IPHONE16P-256", "iPhone 16 Pro 256GB", validPrice, "VND", "ACTIVE"
        );

        BigDecimal storedPrice = jdbcTemplate.queryForObject(
                "SELECT price FROM skus WHERE id = ?", BigDecimal.class, skuId
        );
        assertThat(storedPrice).isEqualByComparingTo(validPrice);
    }

    @Test
    @DisplayName("Negative: Category parent pointing to self violates chk_category_parent_not_self constraint")
    void testCategorySelfParent_Rejected() {
        UUID categoryId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO categories (id, name, slug, parent_id, status, display_order) VALUES (?, ?, ?, ?, ?, ?)",
                categoryId, "Self Parent Category", "self-parent-" + categoryId, categoryId, "ACTIVE", 0
        )).isNotNull();
    }

    @Test
    @DisplayName("Negative: Duplicate category slug violates unique constraint")
    void testDuplicateCategorySlug_Rejected() {
        String slug = "duplicate-category-slug";
        jdbcTemplate.update(
                "INSERT INTO categories (id, name, slug, status, display_order) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), "Cat 1", slug, "ACTIVE", 0
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO categories (id, name, slug, status, display_order) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), "Cat 2", slug, "ACTIVE", 0
        )).isNotNull();
    }

    @Test
    @DisplayName("Negative: Negative SKU price violates price check constraint")
    void testNegativeSkuPrice_Rejected() {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO categories (id, name, slug) VALUES (?, ?, ?)", categoryId, "C", "c-" + categoryId);
        UUID brandId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO brands (id, name, slug) VALUES (?, ?, ?)", brandId, "B", "b-" + brandId);
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO products (id, name, category_id, brand_id) VALUES (?, ?, ?, ?)", productId, "P", categoryId, brandId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO skus (id, product_id, sku_code, name, price, currency) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), productId, "SKU-NEG-PRICE", "Negative SKU", new BigDecimal("-1.0000"), "VND"
        )).isNotNull();
    }

    @Test
    @DisplayName("Negative: SKU price exceeding upper bound 999999999999.9999 violates constraint")
    void testSkuPriceExceedingUpperBound_Rejected() {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO categories (id, name, slug) VALUES (?, ?, ?)", categoryId, "C2", "c2-" + categoryId);
        UUID brandId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO brands (id, name, slug) VALUES (?, ?, ?)", brandId, "B2", "b2-" + brandId);
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO products (id, name, category_id, brand_id) VALUES (?, ?, ?, ?)", productId, "P2", categoryId, brandId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO skus (id, product_id, sku_code, name, price, currency) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), productId, "SKU-EXCEED-PRICE", "Exceed SKU", new BigDecimal("1000000000000.0000"), "VND"
        )).isNotNull();
    }

    @Test
    @DisplayName("Negative: Non-VND currency violates currency check constraint")
    void testNonVndCurrency_Rejected() {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO categories (id, name, slug) VALUES (?, ?, ?)", categoryId, "C3", "c3-" + categoryId);
        UUID brandId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO brands (id, name, slug) VALUES (?, ?, ?)", brandId, "B3", "b3-" + brandId);
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO products (id, name, category_id, brand_id) VALUES (?, ?, ?, ?)", productId, "P3", categoryId, brandId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO skus (id, product_id, sku_code, name, price, currency) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), productId, "SKU-USD-PRICE", "USD SKU", new BigDecimal("100.0000"), "USD"
        )).isNotNull();
    }

    @Test
    @DisplayName("Negative: Duplicate primary image for product violates partial unique index")
    void testDuplicateProductPrimaryImage_Rejected() {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO categories (id, name, slug) VALUES (?, ?, ?)", categoryId, "C4", "c4-" + categoryId);
        UUID brandId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO brands (id, name, slug) VALUES (?, ?, ?)", brandId, "B4", "b4-" + brandId);
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO products (id, name, category_id, brand_id) VALUES (?, ?, ?, ?)", productId, "P4", categoryId, brandId);

        jdbcTemplate.update(
                "INSERT INTO product_images (id, product_id, url, is_primary, display_order) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), productId, "https://example.com/img1.jpg", true, 0
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO product_images (id, product_id, url, is_primary, display_order) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), productId, "https://example.com/img2.jpg", true, 1
        )).isNotNull();
    }
}
