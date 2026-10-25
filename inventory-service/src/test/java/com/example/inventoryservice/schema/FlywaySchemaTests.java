package com.example.inventoryservice.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=${INVENTORY_TEST_DB_URL:jdbc:postgresql://localhost:5432/inventory_db}",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres",
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
class FlywaySchemaTests {

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
    @DisplayName("Positive: Flyway migrations execute successfully and create core tables")
    void testTablesExist() {
        Integer warehouseCount = jdbcTemplate.queryForObject("SELECT count(*) FROM warehouses", Integer.class);
        assertThat(warehouseCount).isNotNull().isGreaterThanOrEqualTo(1);

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
    @DisplayName("Positive: Default warehouse is seeded as WH_DEFAULT and ACTIVE")
    void testDefaultWarehouseSeeded() {
        Boolean isDefault = jdbcTemplate.queryForObject(
                "SELECT is_default FROM warehouses WHERE code = 'WH_DEFAULT'", Boolean.class);
        assertThat(isDefault).isTrue();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM warehouses WHERE code = 'WH_DEFAULT'", String.class);
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Negative: Two active default warehouses violate partial unique index")
    void testDuplicateActiveDefaultWarehouse_Rejected() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO warehouses (name, code, is_default, status) VALUES (?, ?, TRUE, 'ACTIVE')",
                "Another Default", "WH_DEFAULT_2"
        )).isNotNull();
    }

    @Test
    @DisplayName("Negative: Stock on_hand less than reserved violates check constraint")
    void testStockOnHandLessThanReserved_Rejected() {
        UUID warehouseId = jdbcTemplate.queryForObject(
                "SELECT id FROM warehouses WHERE code = 'WH_DEFAULT'", UUID.class);
        UUID skuId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO stocks (sku_id, warehouse_id, on_hand, reserved, version) VALUES (?, ?, 5, 10, 0)",
                skuId, warehouseId
        )).isNotNull();
    }

    @Test
    @DisplayName("Positive: Stored generated column available equals on_hand - reserved")
    void testGeneratedColumnAvailable() {
        UUID warehouseId = jdbcTemplate.queryForObject(
                "SELECT id FROM warehouses WHERE code = 'WH_DEFAULT'", UUID.class);
        UUID skuId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO stocks (sku_id, warehouse_id, on_hand, reserved, version) VALUES (?, ?, 10, 3, 0)",
                skuId, warehouseId
        );

        Integer available = jdbcTemplate.queryForObject(
                "SELECT available FROM stocks WHERE sku_id = ? AND warehouse_id = ?",
                Integer.class, skuId, warehouseId
        );
        assertThat(available).isEqualTo(7);
    }

    @Test
    @DisplayName("Negative: Append-only trigger rejects UPDATE on stock_movements")
    void testStockMovementUpdate_Rejected() {
        UUID warehouseId = jdbcTemplate.queryForObject(
                "SELECT id FROM warehouses WHERE code = 'WH_DEFAULT'", UUID.class);
        UUID movementId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO stock_movements (id, sku_id, warehouse_id, movement_type, quantity_delta,
                                            reference_type, reference_id, before_on_hand, after_on_hand,
                                            before_reserved, after_reserved, reason)
                VALUES (?, ?, ?, 'INBOUND', 10, 'ADJUSTMENT', 'ref-1', 0, 10, 0, 0, 'Initial')
                """,
                movementId, skuId, warehouseId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE stock_movements SET reason = 'Modified' WHERE id = ?", movementId
        )).hasMessageContaining("stock_movements is append-only");
    }

    @Test
    @DisplayName("Negative: Append-only trigger rejects DELETE on stock_movements")
    void testStockMovementDelete_Rejected() {
        UUID warehouseId = jdbcTemplate.queryForObject(
                "SELECT id FROM warehouses WHERE code = 'WH_DEFAULT'", UUID.class);
        UUID movementId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO stock_movements (id, sku_id, warehouse_id, movement_type, quantity_delta,
                                            reference_type, reference_id, before_on_hand, after_on_hand,
                                            before_reserved, after_reserved, reason)
                VALUES (?, ?, ?, 'INBOUND', 10, 'ADJUSTMENT', 'ref-del', 0, 10, 0, 0, 'Initial')
                """,
                movementId, skuId, warehouseId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM stock_movements WHERE id = ?", movementId
        )).hasMessageContaining("stock_movements is append-only");
    }
}
