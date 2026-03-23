package com.example.inventory.service;

import com.example.inventory.entity.Product;
import com.example.inventory.entity.Promotion;
import com.example.inventory.repository.ProductRepository;
import com.example.inventory.repository.PromotionRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 技术点5: CompletableFuture 并发聚合 + 超时降级
 *
 * 商品详情页需要聚合多个数据源：
 * 1. 商品基本信息（从 Redis 缓存读取）
 * 2. 实时库存（从 DB 读取最新值）
 * 3. 促销信息（从 DB 读取）
 *
 * 三个查询并发执行，设置超时，任一数据源超时则降级返回默认值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductDetailService {

    private final ProductCacheService cacheService;
    private final ProductRepository productRepo;
    private final PromotionRepository promotionRepo;
    private final ExecutorService queryExecutor;

    /** 商品详情聚合结果 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductDetailVO {
        private Product product;
        private Integer realtimeStock;       // 实时库存
        private List<Promotion> promotions;  // 促销活动
        private Map<String, String> degradeInfo;  // 降级信息（如果有）
    }

    /**
     * 并发聚合商品详情
     * 三个查询同时发出，整体超时 500ms，超时的部分降级
     */
    public ProductDetailVO getProductDetail(String skuId) {
        // ====== 并发发起三个查询 ======

        // 查询1: 商品基本信息（走 Redis 缓存 → 技术点6）
        CompletableFuture<Product> productFuture = CompletableFuture.supplyAsync(
                () -> cacheService.getBySkuId(skuId).orElse(null),
                queryExecutor
        );

        // 查询2: 实时库存（直接查 DB，不走缓存，保证准确性）
        CompletableFuture<Integer> stockFuture = CompletableFuture.supplyAsync(
                () -> productRepo.findBySkuId(skuId).map(Product::getStock).orElse(0),
                queryExecutor
        );

        // 查询3: 促销信息
        CompletableFuture<List<Promotion>> promoFuture = CompletableFuture.supplyAsync(
                () -> {
                    LocalDateTime now = LocalDateTime.now();
                    return promotionRepo.findBySkuIdAndStartTimeBeforeAndEndTimeAfter(skuId, now, now);
                },
                queryExecutor
        );

        // ====== 聚合结果，带超时降级 ======
        ProductDetailVO vo = new ProductDetailVO();
        java.util.Map<String, String> degradeInfo = new java.util.HashMap<>();

        // 获取商品信息（超时500ms → 降级）
        try {
            Product product = productFuture.get(500, TimeUnit.MILLISECONDS);
            vo.setProduct(product);
        } catch (Exception e) {
            log.warn("[降级] 商品基本信息查询超时/失败 skuId={}", skuId);
            degradeInfo.put("product", "基本信息暂不可用");
        }

        // 获取实时库存（超时300ms → 降级显示缓存库存）
        try {
            vo.setRealtimeStock(stockFuture.get(300, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            log.warn("[降级] 实时库存查询超时/失败 skuId={}", skuId);
            // 降级：使用缓存中的库存值
            vo.setRealtimeStock(vo.getProduct() != null ? vo.getProduct().getStock() : 0);
            degradeInfo.put("stock", "库存数据可能有延迟");
        }

        // 获取促销信息（超时300ms → 降级不显示促销）
        try {
            vo.setPromotions(promoFuture.get(300, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            log.warn("[降级] 促销信息查询超时/失败 skuId={}", skuId);
            vo.setPromotions(Collections.emptyList());
            degradeInfo.put("promotion", "促销信息暂不可用");
        }

        vo.setDegradeInfo(degradeInfo.isEmpty() ? null : degradeInfo);
        return vo;
    }
}
