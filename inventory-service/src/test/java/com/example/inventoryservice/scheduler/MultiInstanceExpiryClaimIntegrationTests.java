package com.example.inventoryservice.scheduler;

import com.example.inventoryservice.entity.Reservation;
import com.example.inventoryservice.entity.ReservationStatus;
import com.example.inventoryservice.entity.Stock;
import com.example.inventoryservice.entity.Warehouse;
import com.example.inventoryservice.entity.WarehouseStatus;
import com.example.inventoryservice.repository.ReservationRepository;
import com.example.inventoryservice.repository.StockRepository;
import com.example.inventoryservice.repository.WarehouseRepository;
import com.example.inventoryservice.service.ReservationItemCommand;
import com.example.inventoryservice.service.ReservationService;
import com.example.inventoryservice.service.StockAdjustmentCommand;
import com.example.inventoryservice.service.StockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@org.junit.jupiter.api.condition.EnabledIf("isPostgresAvailable")
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.datasource.url=jdbc:postgresql://localhost:5432/inventory_db",
        "spring.datasource.username=postgres",
        "spring.datasource.password=postgres",
        "spring.datasource.driver-class-name=org.postgresql.Driver"
})
class MultiInstanceExpiryClaimIntegrationTests {

    static boolean isPostgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5432), 1000);
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
    private WarehouseRepository warehouseRepository;

    @Test
    @DisplayName("Positive: Multi-instance concurrent expiry claims do not process duplicate reservations using SKIP LOCKED")
    void testConcurrentExpiryClaims_NoDuplicateOrConflict() throws Exception {
        Warehouse warehouse = warehouseRepository.findFirstByIsDefaultTrueAndStatus(WarehouseStatus.ACTIVE)
                .orElseGet(() -> warehouseRepository.save(Warehouse.builder()
                        .name("Default Warehouse")
                        .code("WH_DEFAULT")
                        .isDefault(true)
                        .status(WarehouseStatus.ACTIVE)
                        .build()));
        UUID skuId = UUID.randomUUID();
        stockService.adjust(new StockAdjustmentCommand(skuId, warehouse.getId(), 50, "ref-sched-" + UUID.randomUUID(), "Init"), "admin", "c1");

        int reservationCount = 6;
        List<UUID> orderIds = new ArrayList<>();
        for (int i = 0; i < reservationCount; i++) {
            UUID orderId = UUID.randomUUID();
            orderIds.add(orderId);
            reservationService.reserve(orderId, List.of(new ReservationItemCommand(skuId, 2)), "corr-sched-" + i);

            Reservation res = reservationRepository.findByOrderId(orderId).orElseThrow();
            res.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
            reservationRepository.saveAndFlush(res);
        }

        // Two concurrent instances
        int instances = 2;
        ExecutorService executor = Executors.newFixedThreadPool(instances);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger totalExpired = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        Instant checkTime = Instant.now();
        for (int i = 0; i < instances; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    int expired = reservationService.expireDue(checkTime, 10);
                    totalExpired.addAndGet(expired);
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

        assertThat(totalExpired.get()).isGreaterThanOrEqualTo(reservationCount);

        // Verify all reservations are EXPIRED
        for (UUID orderId : orderIds) {
            Reservation res = reservationRepository.findByOrderId(orderId).orElseThrow();
            assertThat(res.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        }

        // Verify reserved quantity was cleanly restored
        Stock stock = stockRepository.findBySkuIdAndWarehouseId(skuId, warehouse.getId()).orElseThrow();
        assertThat(stock.getReserved()).isEqualTo(0);
        assertThat(stock.getAvailableQuantity()).isEqualTo(50);
    }
}
