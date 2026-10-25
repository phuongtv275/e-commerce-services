package com.example.inventoryservice.service;

import com.ecommerce.common.exception.ValidationException;
import com.example.inventoryservice.dto.BatchStockResponse;
import com.example.inventoryservice.entity.Stock;
import com.example.inventoryservice.entity.Warehouse;
import com.example.inventoryservice.entity.WarehouseStatus;
import com.example.inventoryservice.repository.StockMovementRepository;
import com.example.inventoryservice.repository.StockRepository;
import com.example.inventoryservice.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockServiceBatchAvailabilityTests {

    @Mock
    private StockRepository stockRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private WarehouseRepository warehouseRepository;

    @Mock
    private InventoryOutboxService outboxService;

    private StockServiceImpl stockService;

    private Warehouse defaultWarehouse;

    @BeforeEach
    void setUp() {
        stockService = new StockServiceImpl(
                stockRepository,
                stockMovementRepository,
                warehouseRepository,
                outboxService
        );

        defaultWarehouse = Warehouse.builder()
                .id(UUID.randomUUID())
                .name("Default Warehouse")
                .code("WH_DEFAULT")
                .isDefault(true)
                .status(WarehouseStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("Positive: Returns available quantities for existing SKUs and records missing SKUs")
    void testGetBatchStockAvailability_Success() {
        when(warehouseRepository.findFirstByIsDefaultTrueAndStatus(WarehouseStatus.ACTIVE))
                .thenReturn(Optional.of(defaultWarehouse));

        UUID knownSku1 = UUID.randomUUID();
        UUID knownSku2 = UUID.randomUUID();
        UUID missingSku = UUID.randomUUID();

        Stock stock1 = Stock.builder()
                .id(UUID.randomUUID())
                .skuId(knownSku1)
                .warehouse(defaultWarehouse)
                .onHand(50)
                .reserved(10)
                .version(1L)
                .updatedAt(Instant.now())
                .build();

        Stock stock2 = Stock.builder()
                .id(UUID.randomUUID())
                .skuId(knownSku2)
                .warehouse(defaultWarehouse)
                .onHand(20)
                .reserved(20)
                .version(1L)
                .updatedAt(Instant.now())
                .build();

        when(stockRepository.findBySkuIdInAndWarehouseId(any(), eq(defaultWarehouse.getId())))
                .thenReturn(List.of(stock1, stock2));

        BatchStockResponse result = stockService.getBatchStockAvailability(List.of(knownSku1, knownSku2, missingSku));

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).skuId()).isEqualTo(knownSku1);
        assertThat(result.items().get(0).availableQuantity()).isEqualTo(40);
        assertThat(result.items().get(1).skuId()).isEqualTo(knownSku2);
        assertThat(result.items().get(1).availableQuantity()).isEqualTo(0);

        assertThat(result.missingSkuIds()).containsExactly(missingSku);
    }

    @Test
    @DisplayName("Negative: Empty list throws ValidationException")
    void testGetBatchStockAvailability_EmptyList() {
        assertThatThrownBy(() -> stockService.getBatchStockAvailability(Collections.emptyList()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("skuIds list cannot be empty");
    }

    @Test
    @DisplayName("Negative: List exceeding 100 items throws ValidationException")
    void testGetBatchStockAvailability_OversizedList() {
        List<UUID> list = IntStream.range(0, 101)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        assertThatThrownBy(() -> stockService.getBatchStockAvailability(list))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot exceed 100 items");
    }

    @Test
    @DisplayName("Negative: Null SKU ID in list throws ValidationException")
    void testGetBatchStockAvailability_NullSkuId() {
        List<UUID> list = Collections.singletonList(null);

        assertThatThrownBy(() -> stockService.getBatchStockAvailability(list))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("skuId cannot be null");
    }
}
