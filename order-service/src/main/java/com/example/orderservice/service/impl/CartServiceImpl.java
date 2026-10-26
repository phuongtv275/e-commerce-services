package com.example.orderservice.service.impl;

import com.ecommerce.common.constant.ErrorCode;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.exception.ResourceNotFoundException;
import com.example.orderservice.client.CatalogClient;
import com.example.orderservice.client.InventoryClient;
import com.example.orderservice.client.dto.*;
import com.example.orderservice.dto.*;
import com.example.orderservice.service.CartMutationTxService;
import com.example.orderservice.service.CartMutationTxService.CartData;
import com.example.orderservice.service.CartMutationTxService.CartItemSnapshot;
import com.example.orderservice.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartMutationTxService cartMutationTxService;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;

    @Override
    public CartResponse getCart(UUID customerId) {
        // 1. Read cart in short dedicated transaction
        CartData cartData = cartMutationTxService.loadCartData(customerId);
        if (cartData.items().isEmpty()) {
            return CartResponse.builder()
                    .cartId(cartData.cartId())
                    .items(Collections.emptyList())
                    .subtotal(BigDecimal.ZERO)
                    .warnings(Collections.emptyList())
                    .build();
        }

        // 2. Perform remote lookups strictly outside DB transaction
        return buildCartResponseWithRemoteLookups(cartData);
    }

    @Override
    public CartResponse addToCart(UUID customerId, AddToCartRequest request) {
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Số lượng phải lớn hơn 0");
        }

        // 1. Remote Catalog validation strictly outside DB transaction
        CatalogBatchSkuResponse catalogResponse = catalogClient.getBatchSkus(List.of(request.getSkuId()));
        if (catalogResponse == null || catalogResponse.getItems() == null || catalogResponse.getItems().isEmpty()) {
            throw new ResourceNotFoundException("Sản phẩm không tồn tại");
        }

        CatalogSkuItemResponse sku = catalogResponse.getItems().getFirst();
        if (!"ACTIVE".equalsIgnoreCase(sku.getStatus())) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Sản phẩm hiện không mở bán");
        }

        // 2. Mutate cart in short dedicated local transaction
        cartMutationTxService.mutateAddToCart(customerId, sku, request.getQuantity());

        // 3. Return cart with remote lookups outside DB transaction
        return getCart(customerId);
    }

    @Override
    public CartResponse updateCartItem(UUID customerId, UUID itemId, UpdateCartItemRequest request) {
        // 1. Mutate in short dedicated local transaction
        cartMutationTxService.mutateUpdateCartItem(customerId, itemId, request.getQuantity());

        // 2. Return cart outside DB transaction
        return getCart(customerId);
    }

    @Override
    public void removeCartItem(UUID customerId, UUID itemId) {
        cartMutationTxService.mutateRemoveCartItem(customerId, itemId);
    }

    @Override
    public void clearCart(UUID customerId) {
        cartMutationTxService.mutateClearCart(customerId);
    }

    private CartResponse buildCartResponseWithRemoteLookups(CartData cartData) {
        List<UUID> skuIds = cartData.items().stream()
                .map(CartItemSnapshot::skuId)
                .toList();

        // Remote lookups (catalog current price and inventory stock batch)
        CatalogBatchSkuResponse catalogBatch = catalogClient.getBatchSkus(skuIds);
        Map<UUID, CatalogSkuItemResponse> catalogMap = catalogBatch.getItems().stream()
                .collect(Collectors.toMap(CatalogSkuItemResponse::getSkuId, Function.identity(), (a, b) -> a));

        InventoryStockBatchResponse stockBatch = inventoryClient.getBatchStocks(skuIds);
        Map<UUID, Integer> stockMap = stockBatch.getItems().stream()
                .collect(Collectors.toMap(InventoryStockItemResponse::getSkuId, InventoryStockItemResponse::getAvailableQuantity, (a, b) -> a));

        List<CartItemResponse> itemResponses = new ArrayList<>();
        List<CartWarningResponse> warnings = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (CartItemSnapshot item : cartData.items()) {
            CatalogSkuItemResponse catalogSku = catalogMap.get(item.skuId());
            boolean isMissing = catalogSku == null || catalogBatch.getMissingSkuIds().contains(item.skuId());

            BigDecimal effectivePrice = item.unitPrice();
            String productName = item.skuCode();
            String skuName = null;

            if (isMissing) {
                warnings.add(CartWarningResponse.builder()
                        .type("SKU_UNAVAILABLE")
                        .message("Sản phẩm không còn tồn tại")
                        .skuId(item.skuId())
                        .build());
            } else {
                productName = catalogSku.getProductName();
                skuName = catalogSku.getSkuName();
                effectivePrice = catalogSku.getPrice();

                if (!"ACTIVE".equalsIgnoreCase(catalogSku.getStatus())) {
                    warnings.add(CartWarningResponse.builder()
                            .type("SKU_INACTIVE")
                            .message("Sản phẩm đã ngừng kinh doanh")
                            .skuId(item.skuId())
                            .build());
                }

                // Check price change
                if (item.unitPrice() != null && catalogSku.getPrice() != null
                        && item.unitPrice().compareTo(catalogSku.getPrice()) != 0) {
                    warnings.add(CartWarningResponse.builder()
                            .type("PRICE_CHANGED")
                            .message(String.format("Giá sản phẩm đã thay đổi từ %s sang %s", item.unitPrice(), catalogSku.getPrice()))
                            .skuId(item.skuId())
                            .build());
                }

                // Check stock availability
                Integer availableQty = stockMap.get(item.skuId());
                if (availableQty == null || availableQty < item.quantity()) {
                    warnings.add(CartWarningResponse.builder()
                            .type("INSUFFICIENT_STOCK")
                            .message(String.format("Số lượng tồn kho (%s) không đủ đáp ứng số lượng trong giỏ (%s)",
                                    availableQty != null ? availableQty : 0, item.quantity()))
                            .skuId(item.skuId())
                            .build());
                }
            }

            BigDecimal lineTotal = effectivePrice.multiply(BigDecimal.valueOf(item.quantity()));
            subtotal = subtotal.add(lineTotal);

            itemResponses.add(CartItemResponse.builder()
                    .itemId(item.itemId())
                    .skuId(item.skuId())
                    .skuCode(item.skuCode())
                    .productName(productName)
                    .skuName(skuName)
                    .unitPrice(effectivePrice)
                    .quantity(item.quantity())
                    .lineTotal(lineTotal)
                    .addedAt(item.addedAt())
                    .build());
        }

        return CartResponse.builder()
                .cartId(cartData.cartId())
                .items(itemResponses)
                .subtotal(subtotal)
                .warnings(warnings)
                .build();
    }
}
