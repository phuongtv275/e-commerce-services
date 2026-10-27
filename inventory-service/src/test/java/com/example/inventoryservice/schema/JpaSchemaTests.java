package com.example.inventoryservice.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

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
        "spring.datasource.url=${INVENTORY_TEST_DB_URL:jdbc:postgresql://localhost:5432/inventory_db}",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres",
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
class JpaSchemaTests {

    static boolean isPostgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(System.getenv().getOrDefault("INVENTORY_TEST_DB_HOST", "localhost"), 5432), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Positive: Hibernate DDL creates core inventory tables successfully")
    void testTablesExist() {
        Integer warehouseCount = jdbcTemplate.queryForObject("SELECT count(*) FROM warehouses", Integer.class);
        assertThat(warehouseCount).isNotNull();

        Integer stockCount = jdbcTemplate.queryForObject("SELECT count(*) FROM stocks", Integer.class);
        assertThat(stockCount).isNotNull();

        Integer movementCount = jdbcTemplate.queryForObject("SELECT count(*) FROM stock_movements", Integer.class);
        assertThat(movementCount).isNotNull();

        Integer reservationCount = jdbcTemplate.queryForObject("SELECT count(*) FROM reservations", Integer.class);
        assertThat(reservationCount).isNotNull();

        Integer reservationItemCount = jdbcTemplate.queryForObject("SELECT count(*) FROM reservation_items", Integer.class);
        assertThat(reservationItemCount).isNotNull();

        Integer quarantineCount = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_quarantine_logs", Integer.class);
        assertThat(quarantineCount).isNotNull();

        Integer outboxCount = jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
        assertThat(outboxCount).isNotNull();

        Integer processedCount = jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Integer.class);
        assertThat(processedCount).isNotNull();
    }

    @Test
    @DisplayName("Positive: Warehouse and Stock entities can be persisted and queried")
    void testWarehouseAndStockPersistence() {
        UUID warehouseId = UUID.randomUUID();
        String code = "WH_TEST_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO warehouses (id, name, code, is_default, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                warehouseId, "Test Warehouse", code, false, "ACTIVE"
        );

        UUID stockId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO stocks (id, sku_id, warehouse_id, on_hand, reserved, version, updated_at) VALUES (?, ?, ?, 10, 2, 0, now())",
                stockId, skuId, warehouseId
        );

        Integer onHand = jdbcTemplate.queryForObject("SELECT on_hand FROM stocks WHERE id = ?", Integer.class, stockId);
        assertThat(onHand).isEqualTo(10);
    }

    @Test
    @DisplayName("Negative: Duplicate warehouse code violates unique constraint")
    void testDuplicateWarehouseCode_Rejected() {
        String code = "WH_UNIQUE_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO warehouses (id, name, code, is_default, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), "WH 1", code, false, "ACTIVE"
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO warehouses (id, name, code, is_default, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), "WH 2", code, false, "ACTIVE"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Negative: Duplicate SKU and Warehouse pair in stocks violates unique constraint")
    void testDuplicateStockSkuWarehouse_Rejected() {
        UUID warehouseId = UUID.randomUUID();
        String code = "WH_STOCK_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO warehouses (id, name, code, is_default, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                warehouseId, "WH Stock", code, false, "ACTIVE"
        );

        UUID skuId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO stocks (id, sku_id, warehouse_id, on_hand, reserved, version, updated_at) VALUES (?, ?, ?, 5, 0, 0, now())",
                UUID.randomUUID(), skuId, warehouseId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO stocks (id, sku_id, warehouse_id, on_hand, reserved, version, updated_at) VALUES (?, ?, ?, 10, 0, 0, now())",
                UUID.randomUUID(), skuId, warehouseId
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
