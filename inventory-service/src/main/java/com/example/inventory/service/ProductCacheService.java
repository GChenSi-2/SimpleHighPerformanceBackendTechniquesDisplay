package com.example.inventory.service;

import com.example.inventory.entity.Product;
import com.example.inventory.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 技术点6: Redis 缓存策略
 *
 * 1. 热点商品缓存到 Redis，减少 DB 读压力
 * 2. 互斥锁防缓存击穿：缓存失效时，只有一个线程回源查 DB，其他线程等待
 * 3. TTL 随机抖动防缓存雪崩：每个 key 的过期时间加上随机偏移，避免大量 key 同时过期
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ProductRepository productRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.cache.product-ttl-seconds}")
    private int baseTtl;

    @Value("${app.cache.product-ttl-jitter-seconds}")
    private int ttlJitter;

    private static final String CACHE_PREFIX = "product:";
    private static final String LOCK_PREFIX = "lock:product:";
    private static final String NULL_VALUE = "NULL"; // 缓存穿透保护

    /**
     * 读取商品信息，带缓存
     */
    public Optional<Product> getBySkuId(String skuId) {
        String cacheKey = CACHE_PREFIX + skuId;

        // 1. 先查 Redis
        String cached = redisTemplate.opsForValue().get(cacheKey);

        if (cached != null) {
            if (NULL_VALUE.equals(cached)) {
                // 缓存穿透保护：之前查过DB，不存在，缓存了空值
                return Optional.empty();
            }
            try {
                return Optional.of(objectMapper.readValue(cached, Product.class));
            } catch (Exception e) {
                log.warn("[缓存] 反序列化失败，回源查DB skuId={}", skuId);
            }
        }

        // 2. 缓存未命中 → 互斥锁回源
        return loadWithMutex(skuId, cacheKey);
    }

    /**
     * 互斥锁防击穿
     * 只有拿到锁的线程去查 DB 并写缓存，其他线程短暂等待后重试读缓存
     */
    private Optional<Product> loadWithMutex(String skuId, String cacheKey) {
        String lockKey = LOCK_PREFIX + skuId;

        // 尝试获取分布式锁（SET NX EX）
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(locked)) {
            try {
                // 双重检查：拿到锁后再查一次缓存（可能其他线程刚写入）
                String cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached != null && !NULL_VALUE.equals(cached)) {
                    return Optional.of(objectMapper.readValue(cached, Product.class));
                }

                // 查 DB
                Optional<Product> product = productRepo.findBySkuId(skuId);

                if (product.isPresent()) {
                    // 写入缓存，TTL = baseTtl + 随机抖动（防雪崩）
                    int ttl = baseTtl + ThreadLocalRandom.current().nextInt(-ttlJitter, ttlJitter);
                    String json = objectMapper.writeValueAsString(product.get());
                    redisTemplate.opsForValue().set(cacheKey, json, ttl, TimeUnit.SECONDS);
                    log.info("[缓存] 写入 skuId={}, ttl={}s", skuId, ttl);
                } else {
                    // 空值缓存，防止穿透（短TTL）
                    redisTemplate.opsForValue().set(cacheKey, NULL_VALUE, 60, TimeUnit.SECONDS);
                }

                return product;
            } catch (Exception e) {
                log.error("[缓存] 回源查DB失败 skuId={}", skuId, e);
                return productRepo.findBySkuId(skuId);
            } finally {
                // 释放锁
                redisTemplate.delete(lockKey);
            }
        } else {
            // 未拿到锁 → 短暂等待后重试读缓存
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !NULL_VALUE.equals(cached)) {
                try {
                    return Optional.of(objectMapper.readValue(cached, Product.class));
                } catch (Exception e) {
                    // fall through
                }
            }
            // 兜底：直接查 DB
            return productRepo.findBySkuId(skuId);
        }
    }

    /** 删除缓存（写操作后调用） */
    public void evict(String skuId) {
        redisTemplate.delete(CACHE_PREFIX + skuId);
        log.info("[缓存] 已清除 skuId={}", skuId);
    }
}
