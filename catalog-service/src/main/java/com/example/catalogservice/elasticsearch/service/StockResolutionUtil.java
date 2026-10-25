package com.example.catalogservice.elasticsearch.service;

import com.example.catalogservice.elasticsearch.document.ProductSkuDocument;
import com.example.catalogservice.entity.SkuStockSnapshot;

public final class StockResolutionUtil {

    private StockResolutionUtil() {}

    public record ResolvedStock(Boolean inStock, Long inventoryVersion) {}

    public static ResolvedStock resolve(ProductSkuDocument esDoc, SkuStockSnapshot snapshot) {
        if (esDoc == null && snapshot == null) {
            return new ResolvedStock(null, null);
        }
        if (esDoc == null) {
            return new ResolvedStock(snapshot.getInStock(), snapshot.getInventoryVersion());
        }
        if (snapshot == null) {
            return new ResolvedStock(esDoc.getInStock(), esDoc.getInventoryVersion());
        }

        long esVer = esDoc.getInventoryVersion() != null ? esDoc.getInventoryVersion() : -1L;
        long snapVer = snapshot.getInventoryVersion() != null ? snapshot.getInventoryVersion() : -1L;

        // If one is known and the other is unknown (null), preserve known
        if (esDoc.getInStock() == null && snapshot.getInStock() != null) {
            return new ResolvedStock(snapshot.getInStock(), snapshot.getInventoryVersion());
        }
        if (snapshot.getInStock() == null && esDoc.getInStock() != null) {
            return new ResolvedStock(esDoc.getInStock(), esDoc.getInventoryVersion());
        }

        // Both are known or both are unknown: higher version wins
        if (snapVer >= esVer) {
            return new ResolvedStock(snapshot.getInStock(), snapshot.getInventoryVersion());
        } else {
            return new ResolvedStock(esDoc.getInStock(), esDoc.getInventoryVersion());
        }
    }
}
