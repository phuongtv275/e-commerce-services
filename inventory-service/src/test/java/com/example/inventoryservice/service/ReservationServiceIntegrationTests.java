package com.example.inventoryservice.service;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ConflictException;
import com.ecommerce.common.outbox.OutboxEvent;
import com.ecommerce.common.outbox.OutboxEventRepository;
import com.example.inventoryservice.entity.*;
import com.example.inventoryservice.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "inventory.reservation.ttl=10s"
})
class ReservationServiceIntegrationTests {

    static boolean isPostgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(System.getenv().getOrDefault("INVENTORY_TEST_DB_HOST", "localhost"), 5432), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StockService stockService;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private InventoryQuarantineLogRepository quarantineLogRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private Warehouse defaultWarehouse;

    @BeforeEach
    void setUp() {
        defaultWarehouse = warehouseRepository.findFirstByIsDefaultTrueAndStatus(WarehouseStatus.ACTIVE).orElseThrow();
    }

    @Test
    @DisplayName("Positive: Reserve single SKU succeeds and records RESERVE movement and outbox events")
    void testReserveSingleSku_Success() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 20, "ref-" + UUID.randomUUID(), "Init"), "admin", "c1");

        UUID orderId = UUID.randomUUID();
        List<ReservationItemCommand> items = List.of(new ReservationItemCommand(skuId, 5));

        ReservationResult result = reservationService.reserve(orderId, items, "corr-res-1");

        assertThat(result).isNotNull();
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).quantity()).isEqualTo(5);

        Stock stock = stockRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId()).orElseThrow();
        assertThat(stock.getOnHand()).isEqualTo(20);
        assertThat(stock.getReserved()).isEqualTo(5);
        assertThat(stock.getAvailableQuantity()).isEqualTo(15);

        // Verify outbox
        List<OutboxEvent> outboxList = outboxEventRepository.findAll().stream()
                .filter(e -> "RESERVATION".equals(e.getAggregateType()) && result.reservationId().toString().equals(e.getAggregateId()))
                .toList();
        assertThat(outboxList).isNotEmpty();
        assertThat(outboxList.get(0).getEventType()).isEqualTo("inventory.reserved");
    }

    @Test
    @DisplayName("Positive: Duplicate SKUs in reservation request are consolidated")
    void testReserveDuplicateSku_Consolidated() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 20, "ref-" + UUID.randomUUID(), "Init"), "admin", "c1");

        UUID orderId = UUID.randomUUID();
        List<ReservationItemCommand> items = List.of(
                new ReservationItemCommand(skuId, 3),
                new ReservationItemCommand(skuId, 4)
        );

        ReservationResult result = reservationService.reserve(orderId, items, "corr-dup-1");

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).quantity()).isEqualTo(7);

        Stock stock = stockRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId()).orElseThrow();
        assertThat(stock.getReserved()).isEqualTo(7);
        assertThat(stock.getAvailableQuantity()).isEqualTo(13);
    }

    @Test
    @DisplayName("Positive: Idempotent reserve replay with same payload returns existing reservation")
    void testIdempotentReserveReplay() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 20, "ref-" + UUID.randomUUID(), "Init"), "admin", "c1");

        UUID orderId = UUID.randomUUID();
        List<ReservationItemCommand> items = List.of(new ReservationItemCommand(skuId, 5));

        ReservationResult res1 = reservationService.reserve(orderId, items, "corr-1");
        ReservationResult res2 = reservationService.reserve(orderId, items, "corr-1");

        assertThat(res1.reservationId()).isEqualTo(res2.reservationId());

        Stock stock = stockRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId()).orElseThrow();
        assertThat(stock.getReserved()).isEqualTo(5); // Not double-reserved
    }

    @Test
    @DisplayName("Negative: Reusing orderId with different payload throws 409 STATE_CONFLICT")
    void testReusedOrderIdWithDifferentPayload_Conflict() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 20, "ref-" + UUID.randomUUID(), "Init"), "admin", "c1");

        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 5)), "c1");

        assertThatThrownBy(() -> reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 8)), "c2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists with different items");
    }

    @Test
    @DisplayName("Negative: Multi-SKU rollback rolls back 1st SKU when 2nd SKU is insufficient")
    void testMultiSkuRollbackOnInsufficientStock() {
        UUID sku1 = UUID.randomUUID();
        UUID sku2 = UUID.randomUUID();

        stockService.adjust(new StockAdjustmentCommand(sku1, defaultWarehouse.getId(), 10, "ref-" + UUID.randomUUID(), "Init 1"), "admin", "c1");
        stockService.adjust(new StockAdjustmentCommand(sku2, defaultWarehouse.getId(), 2, "ref-" + UUID.randomUUID(), "Init 2"), "admin", "c2");

        UUID orderId = UUID.randomUUID();
        List<ReservationItemCommand> items = List.of(
                new ReservationItemCommand(sku1, 5),
                new ReservationItemCommand(sku2, 5) // Exceeds available 2!
        );

        assertThatThrownBy(() -> reservationService.reserve(orderId, items, "c-fail"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Insufficient stock");

        // Verify sku1 was rolled back completely
        Stock stock1 = stockRepository.findBySkuIdAndWarehouseId(sku1, defaultWarehouse.getId()).orElseThrow();
        assertThat(stock1.getReserved()).isEqualTo(0);
        assertThat(stock1.getAvailableQuantity()).isEqualTo(10);

        // Verify no reservation created
        assertThat(reservationRepository.findByOrderId(orderId)).isEmpty();
    }

    @Test
    @DisplayName("Negative: Oversell race prevents overselling stock under high concurrency")
    void testConcurrentOversellRace() throws Exception {
        UUID skuId = UUID.randomUUID();
        // Provision exactly 1 unit
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 1, "ref-" + UUID.randomUUID(), "Init 1"), "admin", "c1");

        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            UUID orderId = UUID.randomUUID();
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 1)), "c-race");
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    // unexpected
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(concurrency - 1);

        Stock stock = stockRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId()).orElseThrow();
        assertThat(stock.getOnHand()).isEqualTo(1);
        assertThat(stock.getReserved()).isEqualTo(1);
        assertThat(stock.getAvailableQuantity()).isEqualTo(0);
    }

    @Test
    @DisplayName("Positive: Commit flow transitions PENDING to COMMITTED and decrements onHand and reserved")
    void testCommitFlow_Success() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, "ref-" + UUID.randomUUID(), "Init"), "admin", "c1");

        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 4)), "c1");

        reservationService.commit(orderId, "c-commit");

        Reservation reservation = reservationRepository.findByOrderId(orderId).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(reservation.getCommittedAt()).isNotNull();

        Stock stock = stockRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId()).orElseThrow();
        assertThat(stock.getOnHand()).isEqualTo(6);
        assertThat(stock.getReserved()).isEqualTo(0);
        assertThat(stock.getAvailableQuantity()).isEqualTo(6);

        // Duplicate commit is no-op
        reservationService.commit(orderId, "c-commit-dup");
        Stock stockAfterDup = stockRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId()).orElseThrow();
        assertThat(stockAfterDup.getOnHand()).isEqualTo(6);
    }

    @Test
    @DisplayName("Positive: Release flow restores reserved stock and marks reservation RELEASED")
    void testReleaseFlow_Success() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, "ref-" + UUID.randomUUID(), "Init"), "admin", "c1");

        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 4)), "c1");

        ReservationResult released = reservationService.release(orderId, "User cancelled", "c-rel");
        assertThat(released.status()).isEqualTo("RELEASED");

        Stock stock = stockRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId()).orElseThrow();
        assertThat(stock.getOnHand()).isEqualTo(10);
        assertThat(stock.getReserved()).isEqualTo(0);
        assertThat(stock.getAvailableQuantity()).isEqualTo(10);

        // Duplicate release is no-op
        ReservationResult dupRelease = reservationService.release(orderId, "User cancelled again", "c-rel-dup");
        assertThat(dupRelease.status()).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("Positive: Restock for event on COMMITTED reservation restores on_hand")
    void testRestockForEvent_Success() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, "ref-" + UUID.randomUUID(), "Init"), "admin", "c1");

        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 4)), "c1");
        reservationService.commit(orderId, "c-commit");

        reservationService.restockForEvent(orderId, "Order return", "c-restock");

        Reservation res = reservationRepository.findByOrderId(orderId).orElseThrow();
        assertThat(res.getStatus()).isEqualTo(ReservationStatus.RESTOCKED);

        Stock stock = stockRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId()).orElseThrow();
        assertThat(stock.getOnHand()).isEqualTo(10);
        assertThat(stock.getReserved()).isEqualTo(0);

        // Duplicate restock is no-op
        reservationService.restockForEvent(orderId, "Order return dup", "c-restock-dup");
        Stock stockDup = stockRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId()).orElseThrow();
        assertThat(stockDup.getOnHand()).isEqualTo(10);
    }

    @Test
    @DisplayName("Positive: Expire due reservations marks EXPIRED and releases reserved stock")
    void testExpireDueReservations_Success() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, "ref-" + UUID.randomUUID(), "Init"), "admin", "c1");

        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 3)), "c1");

        Reservation res = reservationRepository.findByOrderId(orderId).orElseThrow();
        // Manually backdate expires_at to past
        res.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        reservationRepository.saveAndFlush(res);

        int expiredCount = reservationService.expireDue(Instant.now(), 10);
        assertThat(expiredCount).isGreaterThanOrEqualTo(1);

        Reservation expiredRes = reservationRepository.findByOrderId(orderId).orElseThrow();
        assertThat(expiredRes.getStatus()).isEqualTo(ReservationStatus.EXPIRED);

        Stock stock = stockRepository.findBySkuIdAndWarehouseId(skuId, defaultWarehouse.getId()).orElseThrow();
        assertThat(stock.getReserved()).isEqualTo(0);
        assertThat(stock.getAvailableQuantity()).isEqualTo(10);

        // Verify outbox expired event
        List<OutboxEvent> outboxList = outboxEventRepository.findAll().stream()
                .filter(e -> "RESERVATION".equals(e.getAggregateType()) && expiredRes.getId().toString().equals(e.getAggregateId()))
                .toList();
        boolean hasExpiredEvent = outboxList.stream()
                .anyMatch(e -> e.getEventType().equals("inventory.reservation-expired"));
        assertThat(hasExpiredEvent).isTrue();
    }

    @Test
    @DisplayName("Positive: Quarantine commit failure persists quarantine log and outbox event")
    void testQuarantineCommitFailure_Success() {
        UUID orderId = UUID.randomUUID();
        String payload = "{\"orderId\":\"" + orderId + "\",\"amount\":100}";

        reservationService.quarantineCommitFailure(orderId, "RESERVATION_NOT_FOUND_AFTER_PAYMENT", payload, "c-quar");

        List<InventoryQuarantineLog> logs = quarantineLogRepository.findAll().stream()
                .filter(l -> l.getOrderId().equals(orderId))
                .toList();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getErrorReason()).isEqualTo("RESERVATION_NOT_FOUND_AFTER_PAYMENT");
        assertThat(logs.get(0).getStatus()).isEqualTo("MANUAL_RECONCILIATION_REQUIRED");

        List<OutboxEvent> outboxList = outboxEventRepository.findAll().stream()
                .filter(e -> "RESERVATION".equals(e.getAggregateType()) && orderId.toString().equals(e.getAggregateId()))
                .toList();
        assertThat(outboxList).isNotEmpty();
        assertThat(outboxList.get(0).getEventType()).isEqualTo("inventory.reservation-commit-failed");
    }

    @Test
    @DisplayName("Regression: Commit on EXPIRED reservation records commit-failed outbox event and persists without rollback")
    void testCommitOnExpiredReservation_PersistsOutboxEventAndReturnsNormally() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, "ref-" + UUID.randomUUID(), "Init"), "admin", "c-exp");

        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 3)), "c-exp");

        Reservation res = reservationRepository.findByOrderId(orderId).orElseThrow();
        res.setStatus(ReservationStatus.EXPIRED);
        reservationRepository.saveAndFlush(res);

        // When commit is called on an EXPIRED reservation, it must record the outbox event and return normally without rollback
        reservationService.commit(orderId, "c-commit-expired-success");

        List<OutboxEvent> outboxList = outboxEventRepository.findAll().stream()
                .filter(e -> "RESERVATION".equals(e.getAggregateType()) && res.getId().toString().equals(e.getAggregateId()))
                .toList();
        boolean hasFailedEvent = outboxList.stream()
                .anyMatch(e -> e.getEventType().equals("inventory.reservation-commit-failed"));
        assertThat(hasFailedEvent).isTrue();
    }

    @Test
    @DisplayName("Regression: Commit on RELEASED reservation records commit-failed outbox event and persists without rollback")
    void testCommitOnReleasedReservation_PersistsOutboxEventAndReturnsNormally() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, "ref-" + UUID.randomUUID(), "Init"), "admin", "c-rel");

        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 3)), "c-rel");

        Reservation res = reservationRepository.findByOrderId(orderId).orElseThrow();
        res.setStatus(ReservationStatus.RELEASED);
        reservationRepository.saveAndFlush(res);

        // When commit is called on a RELEASED reservation, it must record the outbox event and return normally without rollback
        reservationService.commit(orderId, "c-commit-released-success");

        List<OutboxEvent> outboxList = outboxEventRepository.findAll().stream()
                .filter(e -> "RESERVATION".equals(e.getAggregateType()) && res.getId().toString().equals(e.getAggregateId()))
                .toList();
        boolean hasFailedEvent = outboxList.stream()
                .anyMatch(e -> e.getEventType().equals("inventory.reservation-commit-failed"));
        assertThat(hasFailedEvent).isTrue();
    }

    @Test
    @DisplayName("Regression: Commit on RESTOCKED reservation records commit-failed outbox event and persists without rollback")
    void testCommitOnRestockedReservation_PersistsOutboxEventAndReturnsNormally() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, "ref-" + UUID.randomUUID(), "Init"), "admin", "c-rst");

        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 3)), "c-rst");

        Reservation res = reservationRepository.findByOrderId(orderId).orElseThrow();
        res.setStatus(ReservationStatus.RESTOCKED);
        reservationRepository.saveAndFlush(res);

        // When commit is called on a RESTOCKED reservation, it must record the outbox event and return normally without rollback
        reservationService.commit(orderId, "c-commit-restocked-success");

        List<OutboxEvent> outboxList = outboxEventRepository.findAll().stream()
                .filter(e -> "RESERVATION".equals(e.getAggregateType()) && res.getId().toString().equals(e.getAggregateId()))
                .toList();
        boolean hasFailedEvent = outboxList.stream()
                .anyMatch(e -> e.getEventType().equals("inventory.reservation-commit-failed"));
        assertThat(hasFailedEvent).isTrue();
    }

    @Test
    @DisplayName("Positive: Configurable reservation TTL sets expiresAt based on inventory.reservation.ttl")
    void testConfigurableReservationTtl_SetsExpiresAt() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 20, "ref-" + UUID.randomUUID(), "Init"), "admin", "c-ttl");

        UUID orderId = UUID.randomUUID();
        Instant before = Instant.now();
        ReservationResult result = reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 5)), "c-ttl");
        Instant after = Instant.now();

        // Configured inventory.reservation.ttl is 10s. If still hardcoded to 15m, this will fail.
        assertThat(result.expiresAt()).isNotNull();
        assertThat(result.expiresAt()).isBetween(before.plus(Duration.ofSeconds(8)), after.plus(Duration.ofSeconds(12)));
    }

    @Test
    @DisplayName("Regression: Commit on PENDING reservation that has passed expiresAt must expire stock and emit commit-failed event, not commit")
    void testCommitOnPendingReservation_PastExpiresAt_ExpiresAndEmitsCommitFailed() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, "ref-" + UUID.randomUUID(), "Init"), "admin", "c-late");

        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 4)), "c-late");

        Reservation res = reservationRepository.findByOrderId(orderId).orElseThrow();
        // Backdate expires_at to 10 minutes ago, keeping status PENDING
        res.setExpiresAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        reservationRepository.saveAndFlush(res);

        // Commit is called when expiresAt < now
        reservationService.commit(orderId, "c-late-commit");

        // Verify reservation was transitioned to EXPIRED, not COMMITTED
        Reservation updated = reservationRepository.findByOrderId(orderId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ReservationStatus.EXPIRED);

        // Verify stock was released, not committed (on_hand remains 10, reserved is 0, available is 10)
        StockBalance balance = stockService.get(skuId, defaultWarehouse.getId());
        assertThat(balance.onHand()).isEqualTo(10);
        assertThat(balance.reserved()).isEqualTo(0);
        assertThat(balance.available()).isEqualTo(10);

        // Verify commit failed outbox event was recorded
        List<OutboxEvent> outboxList = outboxEventRepository.findAll().stream()
                .filter(e -> "RESERVATION".equals(e.getAggregateType()) && res.getId().toString().equals(e.getAggregateId()))
                .toList();
        boolean hasCommitFailed = outboxList.stream()
                .anyMatch(e -> "inventory.reservation-commit-failed".equals(e.getEventType()));
        assertThat(hasCommitFailed).isTrue();
    }

    @Test
    @DisplayName("Regression: Concurrent reserve requests with same orderId do not leak unique constraint DB error")
    void testConcurrentReserveWithSameOrderId_NoUniqueConstraintError() throws Exception {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 50, "ref-" + UUID.randomUUID(), "Init"), "admin", "c-conc");

        UUID orderId = UUID.randomUUID();
        List<ReservationItemCommand> items = List.of(new ReservationItemCommand(skuId, 2));

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        List<Throwable> exceptions = new CopyOnWriteArrayList<>();
        List<ReservationResult> successes = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ReservationResult res = reservationService.reserve(orderId, items, "c-conc");
                    successes.add(res);
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
        assertThat(successes).hasSize(threadCount);

        UUID expectedReservationId = successes.get(0).reservationId();
        assertThat(successes).allMatch(r -> r.reservationId().equals(expectedReservationId));

        // Stock must have been reserved exactly once (reserved = 2, available = 48)
        StockBalance balance = stockService.get(skuId, defaultWarehouse.getId());
        assertThat(balance.reserved()).isEqualTo(2);
        assertThat(balance.available()).isEqualTo(48);
    }

    @Test
    @DisplayName("Regression: Commit expiry race where concurrent commit wins CAS must never emit commit-failed event")
    void testCommitExpiryRace_ConcurrentCommitWins_DoesNotEmitCommitFailed() {
        ReservationRepository reservationRepo = mock(ReservationRepository.class);
        ReservationItemRepository itemRepo = mock(ReservationItemRepository.class);
        StockRepository stockRepo = mock(StockRepository.class);
        StockMovementRepository movementRepo = mock(StockMovementRepository.class);
        WarehouseRepository warehouseRepo = mock(WarehouseRepository.class);
        InventoryQuarantineLogRepository quarantineRepo = mock(InventoryQuarantineLogRepository.class);
        InventoryOutboxService outbox = mock(InventoryOutboxService.class);

        ReservationServiceImpl service = new ReservationServiceImpl(
                reservationRepo, itemRepo, stockRepo, movementRepo, warehouseRepo, quarantineRepo, outbox
        );

        UUID orderId = UUID.randomUUID();
        UUID resId = UUID.randomUUID();

        // 1. Initial read returns PENDING reservation that has expired
        Reservation pendingExpired = Reservation.builder()
                .id(resId)
                .orderId(orderId)
                .status(ReservationStatus.PENDING)
                .expiresAt(Instant.now().minus(10, ChronoUnit.MINUTES))
                .build();
        when(reservationRepo.findByOrderId(orderId)).thenReturn(Optional.of(pendingExpired));

        // 2. CAS to transition to EXPIRED returns 0 (lost race to concurrent commit)
        when(reservationRepo.transitionToExpired(eq(resId), any())).thenReturn(0);

        // 3. Re-read reservation finds it in COMMITTED status
        Reservation committed = Reservation.builder()
                .id(resId)
                .orderId(orderId)
                .status(ReservationStatus.COMMITTED)
                .build();
        when(reservationRepo.findById(resId)).thenReturn(Optional.of(committed));

        // Act
        service.commit(orderId, "c-race");

        // Assert: Never emit commit failed event
        verify(outbox, never()).recordReservationCommitFailedEvent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Regression: transitionToCommitted predicate rejects expired reservations atomically")
    @Transactional
    void testTransitionToCommitted_ExpiredReservation_PredicateRejectsAndReturnsZero() {
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, defaultWarehouse.getId(), 10, "ref-" + UUID.randomUUID(), "Init"), "admin", "c-pred");

        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 2)), "c-pred");
        Reservation res = reservationRepository.findByOrderId(orderId).orElseThrow();

        Instant expiredAt = Instant.now().minusSeconds(5);
        res.setExpiresAt(expiredAt);
        reservationRepository.saveAndFlush(res);

        // Attempting to transition to COMMITTED when now > expires_at must return 0
        int rows = reservationRepository.transitionToCommitted(res.getId(), Instant.now());
        assertThat(rows).isEqualTo(0);

        Reservation after = reservationRepository.findById(res.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }
}
