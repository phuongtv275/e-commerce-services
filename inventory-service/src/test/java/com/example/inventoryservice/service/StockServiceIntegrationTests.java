package com.example.inventoryservice.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.exception.ValidationException;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.inventoryservice.entity.MovementType;
import com.example.inventoryservice.entity.Stock;
import com.example.inventoryservice.entity.StockMovement;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@org.junit.jupiter.api.condition.EnabledIf("isPostgresAvailable")
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
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
        defaultWarehouse = warehouseRepository.findFirstByIsDefaultTrueAndStatus(WarehouseStatus.ACTIVE)
                .orElseGet(() -> warehouseRepository.save(Warehouse.builder()
                        .name("Default Warehouse")
                        .code("WH_DEFAULT")
                        .isDefault(true)
                        .status(WarehouseStatus.ACTIVE)
                        .build()));
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

    @Test
    @DisplayName("Regression: Adjustment replay with different warehouse throws ConflictException")
    void testAdjustmentReplay_DifferentWarehouse_ThrowsConflict() {
        UUID skuId = UUID.randomUUID();
        String refId = "ref-diff-wh-" + UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, refId, "Initial"), "admin", "c1");

        // Create a secondary active warehouse
        Warehouse secondWarehouse = warehouseRepository.save(Warehouse.builder()
                .name("Second WH " + UUID.randomUUID())
                .code("WH_SEC_" + UUID.randomUUID().toString().substring(0, 8))
                .isDefault(false)
                .status(WarehouseStatus.ACTIVE)
                .build());

        StockAdjustmentCommand conflictWhCmd = new StockAdjustmentCommand(skuId, secondWarehouse.getId(), 10, refId, "Initial");
        assertThatThrownBy(() -> stockService.adjust(conflictWhCmd, "admin", "c2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different payload");
    }

    @Test
    @DisplayName("Regression: Adjustment replay with different reason throws ConflictException")
    void testAdjustmentReplay_DifferentReason_ThrowsConflict() {
        UUID skuId = UUID.randomUUID();
        String refId = "ref-diff-reason-" + UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, refId, "Reason A"), "admin", "c1");

        StockAdjustmentCommand conflictReasonCmd = new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, refId, "Reason B");
        assertThatThrownBy(() -> stockService.adjust(conflictReasonCmd, "admin", "c2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different payload");
    }

    @Test
    @DisplayName("Regression: Adjustment replay with different SKU throws ConflictException")
    void testAdjustmentReplay_DifferentSku_ThrowsConflict() {
        UUID sku1 = UUID.randomUUID();
        UUID sku2 = UUID.randomUUID();
        String refId = "ref-diff-sku-" + UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(sku1, defaultWarehouse.getId(), 10, refId, "Reason"), "admin", "c1");

        StockAdjustmentCommand conflictSkuCmd = new StockAdjustmentCommand(sku2, defaultWarehouse.getId(), 10, refId, "Reason");
        assertThatThrownBy(() -> stockService.adjust(conflictSkuCmd, "admin", "c2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different payload");
    }

    @Test
    @DisplayName("Positive: Querying movements with narrow from/to and type returns only matching result")
    void testMovementsFiltering_ByDateAndType_ReturnsFilteredResults() throws Exception {
        UUID skuId = UUID.randomUUID();
        Instant beforeFirst = Instant.now();
        Thread.sleep(10);
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 20, "ref-filter-1-" + UUID.randomUUID(), "Inbound init"), "admin", "c1");
        Instant afterFirst = Instant.now();
        Thread.sleep(10);
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), -5, "ref-filter-2-" + UUID.randomUUID(), "Adjustment decrease"), "admin", "c2");
        Instant afterSecond = Instant.now();

        // Total movements for sku is 2
        Page<StockMovementView> allMovements = stockService.movements(skuId, defaultWarehouse.getId(), PageRequest.of(0, 10));
        assertThat(allMovements.getContent()).hasSize(2);

        // Filter by movementType = INBOUND
        Page<StockMovementView> inboundOnly = stockService.movements(skuId, defaultWarehouse.getId(), null, null, MovementType.INBOUND, PageRequest.of(0, 10));
        assertThat(inboundOnly.getContent()).hasSize(1);
        assertThat(inboundOnly.getContent().get(0).movementType()).isEqualTo("INBOUND");
        assertThat(inboundOnly.getContent().get(0).quantityDelta()).isEqualTo(20);

        // Filter by movementType = ADJUSTMENT
        Page<StockMovementView> adjustmentOnly = stockService.movements(skuId, defaultWarehouse.getId(), null, null, MovementType.ADJUSTMENT, PageRequest.of(0, 10));
        assertThat(adjustmentOnly.getContent()).hasSize(1);
        assertThat(adjustmentOnly.getContent().get(0).movementType()).isEqualTo("ADJUSTMENT");
        assertThat(adjustmentOnly.getContent().get(0).quantityDelta()).isEqualTo(-5);

        // Filter by narrow from/to covering only the first movement
        Page<StockMovementView> dateFiltered = stockService.movements(skuId, defaultWarehouse.getId(), beforeFirst, afterFirst, null, PageRequest.of(0, 10));
        assertThat(dateFiltered.getContent()).hasSize(1);
        assertThat(dateFiltered.getContent().get(0).movementType()).isEqualTo("INBOUND");

        // Filter by narrow from/to and movementType combined
        Page<StockMovementView> combined = stockService.movements(skuId, defaultWarehouse.getId(), afterFirst, afterSecond, MovementType.ADJUSTMENT, PageRequest.of(0, 10));
        assertThat(combined.getContent()).hasSize(1);
        assertThat(combined.getContent().get(0).movementType()).isEqualTo("ADJUSTMENT");
    }

    @Test
    @DisplayName("Regression: Concurrent identical stock adjustments replay with one stock delta and one movement without unique constraint error")
    void testConcurrentIdenticalAdjustments_SingleMovementAndNoUniqueConstraintError() throws Exception {
        UUID skuId = UUID.randomUUID();
        String refId = "ref-conc-adj-" + UUID.randomUUID();
        StockAdjustmentCommand cmd = new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 15, refId, "Concurrent inbound");

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        List<Throwable> exceptions = new CopyOnWriteArrayList<>();
        List<StockBalance> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    StockBalance balance = stockService.adjust(cmd, "admin", "c-conc-adj");
                    results.add(balance);
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(exceptions).isEmpty();
        assertThat(results).hasSize(threadCount);

        // All threads get identical balance (onHand = 15, available = 15)
        for (StockBalance b : results) {
            assertThat(b.onHand()).isEqualTo(15);
            assertThat(b.available()).isEqualTo(15);
        }

        // Final stock row in DB has exactly 15 on hand
        Stock stock = stockRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId()).orElseThrow();
        assertThat(stock.getOnHand()).isEqualTo(15);
        assertThat(stock.getReserved()).isEqualTo(0);
        assertThat(stock.getAvailableQuantity()).isEqualTo(15);

        // Exactly one movement created in DB
        List<StockMovement> movements = stockMovementRepository.findByReferenceTypeAndReferenceId("ADJUSTMENT", refId);
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getQuantityDelta()).isEqualTo(15);
    }
}
