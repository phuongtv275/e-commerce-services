package com.example.catalogservice.elasticsearch;

import com.example.catalogservice.elasticsearch.document.ProductSkuDocument;
import com.example.catalogservice.elasticsearch.service.StockResolutionUtil;
import com.example.catalogservice.entity.SkuStockSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StockResolutionUtilTests {

    @Test
    @DisplayName("Positive: Both null returns null stock and version")
    void testResolve_BothNull() {
        StockResolutionUtil.ResolvedStock stock = StockResolutionUtil.resolve(null, null);
        assertThat(stock.inStock()).isNull();
        assertThat(stock.inventoryVersion()).isNull();
    }

    @Test
    @DisplayName("Positive: Only snapshot returns snapshot values")
    void testResolve_OnlySnapshot() {
        SkuStockSnapshot snap = SkuStockSnapshot.builder()
                .skuId(UUID.randomUUID())
                .inStock(true)
                .inventoryVersion(5L)
                .updatedAt(Instant.now())
                .build();

        StockResolutionUtil.ResolvedStock stock = StockResolutionUtil.resolve(null, snap);
        assertThat(stock.inStock()).isTrue();
        assertThat(stock.inventoryVersion()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Positive: Only ES doc returns ES doc values")
    void testResolve_OnlyEsDoc() {
        ProductSkuDocument doc = ProductSkuDocument.builder()
                .skuId(UUID.randomUUID().toString())
                .inStock(false)
                .inventoryVersion(10L)
                .build();

        StockResolutionUtil.ResolvedStock stock = StockResolutionUtil.resolve(doc, null);
        assertThat(stock.inStock()).isFalse();
        assertThat(stock.inventoryVersion()).isEqualTo(10L);
    }

    @Test
    @DisplayName("Positive: UNKNOWN in ES doc does not overwrite known snapshot stock")
    void testResolve_EsDocUnknown_SnapshotKnown_PreservesKnownSnapshot() {
        ProductSkuDocument doc = ProductSkuDocument.builder()
                .skuId(UUID.randomUUID().toString())
                .inStock(null) // UNKNOWN
                .inventoryVersion(null)
                .build();

        SkuStockSnapshot snap = SkuStockSnapshot.builder()
                .skuId(UUID.randomUUID())
                .inStock(true)
                .inventoryVersion(3L)
                .updatedAt(Instant.now())
                .build();

        StockResolutionUtil.ResolvedStock stock = StockResolutionUtil.resolve(doc, snap);
        assertThat(stock.inStock()).isTrue();
        assertThat(stock.inventoryVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Positive: UNKNOWN in snapshot does not overwrite known ES doc stock")
    void testResolve_SnapshotUnknown_EsDocKnown_PreservesKnownEsDoc() {
        ProductSkuDocument doc = ProductSkuDocument.builder()
                .skuId(UUID.randomUUID().toString())
                .inStock(false)
                .inventoryVersion(8L)
                .build();

        SkuStockSnapshot snap = SkuStockSnapshot.builder()
                .skuId(UUID.randomUUID())
                .inStock(null)
                .inventoryVersion(null)
                .updatedAt(Instant.now())
                .build();

        StockResolutionUtil.ResolvedStock stock = StockResolutionUtil.resolve(doc, snap);
        assertThat(stock.inStock()).isFalse();
        assertThat(stock.inventoryVersion()).isEqualTo(8L);
    }

    @Test
    @DisplayName("Positive: When both known, higher version wins monotonically")
    void testResolve_BothKnown_HigherVersionWins() {
        ProductSkuDocument doc = ProductSkuDocument.builder()
                .skuId(UUID.randomUUID().toString())
                .inStock(true)
                .inventoryVersion(5L)
                .build();

        SkuStockSnapshot newerSnap = SkuStockSnapshot.builder()
                .skuId(UUID.randomUUID())
                .inStock(false)
                .inventoryVersion(6L)
                .updatedAt(Instant.now())
                .build();

        StockResolutionUtil.ResolvedStock stock1 = StockResolutionUtil.resolve(doc, newerSnap);
        assertThat(stock1.inStock()).isFalse();
        assertThat(stock1.inventoryVersion()).isEqualTo(6L);

        SkuStockSnapshot olderSnap = SkuStockSnapshot.builder()
                .skuId(UUID.randomUUID())
                .inStock(false)
                .inventoryVersion(4L)
                .updatedAt(Instant.now())
                .build();

        StockResolutionUtil.ResolvedStock stock2 = StockResolutionUtil.resolve(doc, olderSnap);
        assertThat(stock2.inStock()).isTrue();
        assertThat(stock2.inventoryVersion()).isEqualTo(5L);
    }
}
