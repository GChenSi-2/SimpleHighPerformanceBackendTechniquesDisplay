package com.example.inventory.repository;

import com.example.inventory.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySkuId(String skuId);

    /**
     * 技术点4: 原子 SQL 扣库存
     * WHERE stock >= :qty 保证不会超卖（乐观方式）
     * 返回受影响行数：1=成功，0=库存不足
     */
    @Modifying
    @Query("UPDATE Product p SET p.stock = p.stock - :qty WHERE p.skuId = :skuId AND p.stock >= :qty")
    int deductStock(@Param("skuId") String skuId, @Param("qty") int qty);
}
