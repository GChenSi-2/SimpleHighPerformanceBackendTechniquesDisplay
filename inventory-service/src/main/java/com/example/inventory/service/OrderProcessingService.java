package com.example.inventory.service;

import com.example.inventory.entity.Order;
import com.example.inventory.entity.Outbox;
import com.example.inventory.entity.Product;
import com.example.inventory.kafka.InsufficientStockException;
import com.example.inventory.kafka.OrderEvent;
import com.example.inventory.repository.OrderRepository;
import com.example.inventory.repository.OutboxRepository;
import com.example.inventory.repository.ProcessedEventRepository;
import com.example.inventory.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单处理服务 —— Kafka 消费端的核心逻辑
 *
 * 在一个事务内完成：
 * 1. 幂等检查（INSERT IGNORE t_processed_event）       → 技术点3
 * 2. 原子扣库存（UPDATE ... WHERE stock >= qty）        → 技术点4
 * 3. 创建订单
 * 4. 写 Outbox（同一事务）                              → 技术点2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProcessingService {

    private final ProcessedEventRepository processedEventRepo;
    private final ProductRepository productRepo;
    private final OrderRepository orderRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public void processOrder(OrderEvent event) {
        // ====== 技术点3: 幂等检查 ======
        // INSERT IGNORE: 返回1=首次处理，返回0=已处理过
        int inserted = processedEventRepo.insertIgnore(event.getEventId());
        if (inserted == 0) {
            log.info("[幂等] 事件已处理过，跳过 eventId={}", event.getEventId());
            return;
        }

        // ====== 技术点4: 原子 SQL 扣库存 ======
        int rows = productRepo.deductStock(event.getSkuId(), event.getQuantity());
        if (rows == 0) {
            // 库存不足 → 抛出不可重试异常
            throw new InsufficientStockException(event.getSkuId());
        }

        // ====== 创建订单 ======
        Product product = productRepo.findBySkuId(event.getSkuId())
                .orElseThrow(() -> new RuntimeException("商品不存在: " + event.getSkuId()));

        Order order = new Order();
        order.setOrderNo(event.getOrderNo());
        order.setSkuId(event.getSkuId());
        order.setQuantity(event.getQuantity());
        order.setAmount(product.getPrice().multiply(BigDecimal.valueOf(event.getQuantity())));
        order.setStatus(1); // 已完成
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        orderRepo.save(order);

        // ====== 技术点2: 写 Outbox（同一事务内） ======
        // 订单创建事件写入 outbox，后台定时任务会异步发布到 Kafka
        Outbox outbox = new Outbox();
        outbox.setAggregateType("ORDER");
        outbox.setAggregateId(event.getOrderNo());
        outbox.setEventType("ORDER_CREATED");
        try {
            outbox.setPayload(objectMapper.writeValueAsString(order));
        } catch (Exception e) {
            outbox.setPayload("{}");
        }
        outbox.setStatus(0); // 待发布
        outbox.setCreatedAt(LocalDateTime.now());
        outboxRepo.save(outbox);

        log.info("[订单处理完成] orderNo={}, skuId={}, qty={}", event.getOrderNo(), event.getSkuId(), event.getQuantity());
    }
}
