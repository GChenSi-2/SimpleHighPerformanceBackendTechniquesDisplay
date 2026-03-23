package com.example.inventory.repository;

import com.example.inventory.entity.Promotion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {
    List<Promotion> findBySkuIdAndStartTimeBeforeAndEndTimeAfter(
            String skuId, LocalDateTime now1, LocalDateTime now2);
}
