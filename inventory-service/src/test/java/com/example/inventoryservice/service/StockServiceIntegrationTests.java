package com.example.inventoryservice.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ValidationException;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.inventoryservice.entity.Stock;
import com.example.inventoryservice.entity.Warehouse;
import com.example.inventoryservice.entity.WarehouseStatus;
import com.example.inventoryservice.repository.StockMovementRepository;
import com.example.inventoryservice.repository.StockRepository;
import com.example.inventoryservice.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
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
class StockServiceIntegrationTests {

    static boolean isPostgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(System.getenv().getOrDefault("INVENTORY_TEST_DB_HOST", "localhost"), 5432), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private StockService stockService;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private Warehouse defaultWarehouse;

    @BeforeEach
    void setUp() {
        defaultWarehouse = warehouseRepository.findFirstByIsDefaultTrueAndStatus(WarehouseStatus.ACTIVE).orElseThrow();
    }

    @Test
    @DisplayName("Positive: Initial positive adjustment provisions stock and records INBOUND movement and outbox event")
    void testInitialStockAdjustment_Success() {
        UUID skuId = UUID.randomUUID();
        String refId = "ref-init-" + UUID.randomUUID();

        StockAdjustmentCommand cmd = new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 50, refId, "Initial stock provision");
        StockBalance balance = stockService.adjust(cmd, "warehouse_manager", "corr-init-1");

        assertThat(balance).isNotNull();
        assertThat(balance.skuId()).isEqualTo(skuId);
        assertThat(balance.onHand()).isEqualTo(50);
        assertThat(balance.reserved()).isEqualTo(0);
        assertThat(balance.available()).isEqualTo(50);
        assertThat(balance.version()).isEqualTo(1L);

        // Verify movement
        Page<StockMovementView> movements = stockService.movements(skuId, defaultWarehouse.getId(), PageRequest.of(0, 10));
        assertThat(movements.getContent()).hasSize(1);
        StockMovementView mv = movements.getContent().get(0);
        assertThat(mv.movementType()).isEqualTo("INBOUND");
        assertThat(mv.quantityDelta()).isEqualTo(50);
        assertThat(mv.beforeOnHand()).isEqualTo(0);
        assertThat(mv.afterOnHand()).isEqualTo(50);
        assertThat(mv.referenceId()).isEqualTo(refId);

        // Verify outbox
        List<OutboxEvent> outboxList = outboxEventRepository.findAll().stream()
                .filter(e -> "STOCK".equals(e.getAggregateType()) && skuId.toString().equals(e.getAggregateId()))
                .toList();
        assertThat(outboxList).isNotEmpty();
        assertThat(outboxList.get(0).getEventType()).isEqualTo("inventory.stock.changed");
    }

    @Test
    @DisplayName("Positive: Adjustment on existing stock increments on_hand and updates ledger")
    void testExistingStockAdjustment_Success() {
        UUID skuId = UUID.randomUUID();
        String ref1 = "ref-add-1-" + UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 30, ref1, "Init"), "admin", "c1");

        String ref2 = "ref-add-2-" + UUID.randomUUID();
        StockBalance updated = stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 20, ref2, "Restock"), "admin", "c2");

        assertThat(updated.onHand()).isEqualTo(50);
        assertThat(updated.available()).isEqualTo(50);
        assertThat(updated.version()).isEqualTo(2L);

        Page<StockMovementView> movements = stockService.movements(skuId, defaultWarehouse.getId(), PageRequest.of(0, 10));
        assertThat(movements.getContent()).hasSize(2);
    }

    @Test
    @DisplayName("Positive: Idempotent adjustment retry returns existing balance without duplicate movement")
    void testIdempotentAdjustment_Replay() {
        UUID skuId = UUID.randomUUID();
        String refId = "ref-idem-" + UUID.randomUUID();

        StockAdjustmentCommand cmd = new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 25, refId, "Idempotent batch");
        StockBalance balance1 = stockService.adjust(cmd, "admin", "c1");
        StockBalance balance2 = stockService.adjust(cmd, "admin", "c1");

        assertThat(balance1.onHand()).isEqualTo(25);
        assertThat(balance2.onHand()).isEqualTo(25);

        Page<StockMovementView> movements = stockService.movements(skuId, defaultWarehouse.getId(), PageRequest.of(0, 10));
        assertThat(movements.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("Negative: Reusing referenceId with different delta throws 409 STATE_CONFLICT")
    void testReferenceIdConflict_Rejected() {
        UUID skuId = UUID.randomUUID();
        String refId = "ref-conflict-" + UUID.randomUUID();

        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, refId, "First call"), "admin", "c1");

        StockAdjustmentCommand conflictCmd = new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 20, refId, "Different delta");
        assertThatThrownBy(() -> stockService.adjust(conflictCmd, "admin", "c2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Reference ID already used with different payload");
    }

    @Test
    @DisplayName("Negative: Initial adjustment with non-positive delta throws INVENTORY_INSUFFICIENT")
    void testInitialNegativeAdjustment_Rejected() {
        UUID skuId = UUID.randomUUID();
        String refId = "ref-neg-init-" + UUID.randomUUID();

        StockAdjustmentCommand cmd = new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), -5, refId, "Negative initial");
        assertThatThrownBy(() -> stockService.adjust(cmd, "admin", "c1"))
                .isInstanceOf(BusinessException.class);
    }

    @Autowired
    private ReservationService reservationService;

    @Test
    @DisplayName("Negative: Adjustment resulting in on_hand < reserved violates guard and is rejected")
    void testAdjustmentBelowReserved_Rejected() {
        UUID skuId = UUID.randomUUID();
        String refInit = "ref-guard-init-" + UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, refInit, "Init"), "admin", "c1");

        // Reserve 8 units via reservationService to simulate reservation
        reservationService.reserve(UUID.randomUUID(), List.of(new ReservationItemCommand(skuId, 8)), "c-guard");

        // Try to adjust -5 (remaining on_hand would be 5, but reserved is 8 -> 5 < 8)
        String refAdjust = "ref-guard-sub-" + UUID.randomUUID();
        StockAdjustmentCommand subCmd = new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), -5, refAdjust, "Too much deduction");

        assertThatThrownBy(() -> stockService.adjust(subCmd, "admin", "c2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("less than reserved");
    }

    @Test
    @DisplayName("Negative: Zero quantityDelta violates validation")
    void testZeroQuantityDelta_Rejected() {
        UUID skuId = UUID.randomUUID();
        StockAdjustmentCommand cmd = new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 0, "ref-zero", "Zero delta");
        assertThatThrownBy(() -> stockService.adjust(cmd, "admin", "c1"))
                .isInstanceOf(ValidationException.class);
    }
}
