package com.example.inventory.service;

import com.example.inventory.entity.Product;
import com.example.inventory.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepo;
    private final ProductCacheService cacheService;

    public List<Product> listAll() {
        return productRepo.findAll();
    }

    public Optional<Product> getBySkuId(String skuId) {
        // 走缓存（技术点6）
        return cacheService.getBySkuId(skuId);
    }

    public Product create(Product product) {
        product.setCreatedAt(LocalDateTime.now());
        product.setUpdatedAt(LocalDateTime.now());
        return productRepo.save(product);
    }

    public Product update(String skuId, Product updated) {
        Product existing = productRepo.findBySkuId(skuId)
                .orElseThrow(() -> new RuntimeException("商品不存在: " + skuId));
        existing.setName(updated.getName());
        existing.setPrice(updated.getPrice());
        existing.setStock(updated.getStock());
        existing.setDescription(updated.getDescription());
        existing.setImageUrl(updated.getImageUrl());
        existing.setUpdatedAt(LocalDateTime.now());
        Product saved = productRepo.save(existing);

        // 写操作后清除缓存
        cacheService.evict(skuId);
        return saved;
    }

    public void delete(String skuId) {
        Product existing = productRepo.findBySkuId(skuId)
                .orElseThrow(() -> new RuntimeException("商品不存在: " + skuId));
        productRepo.delete(existing);
        cacheService.evict(skuId);
    }
}
