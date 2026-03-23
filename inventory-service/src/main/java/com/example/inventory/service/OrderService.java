package com.example.inventory.service;

import com.example.inventory.entity.Order;
import com.example.inventory.kafka.OrderEvent;
import com.example.inventory.kafka.OrderKafkaProducer;
import com.example.inventory.repository.OrderRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 订单服务 —— 面向 Controller 的业务层
 *
 * 技术点1: 下单不直接写 DB，而是发消息到 Kafka → 削峰填谷
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderKafkaProducer kafkaProducer;
    private final OrderRepository orderRepo;

    @Data
    public static class PlaceOrderRequest {
        private String skuId;
        private int quantity;
    }

    @Data
    public static class PlaceOrderResponse {
        private String orderNo;
        private String message;

        public PlaceOrderResponse(String orderNo, String message) {
            this.orderNo = orderNo;
            this.message = message;
        }
    }

    /**
     * 下单：生成订单号 → 发送 Kafka 消息 → 立即返回
     * 实际扣库存和创建订单在 Kafka 消费端异步完成
     */
    public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
        String orderNo = "ORD-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String eventId = UUID.randomUUID().toString();

        OrderEvent event = new OrderEvent();
        event.setEventId(eventId);
        event.setOrderNo(orderNo);
        event.setSkuId(request.getSkuId());
        event.setQuantity(request.getQuantity());
        event.setRetryCount(0);

        // 技术点1: 发送到 Kafka，不直接操作 DB
        kafkaProducer.sendOrderEvent(event);

        log.info("[下单] 已提交到Kafka orderNo={}, skuId={}, qty={}",
                orderNo, request.getSkuId(), request.getQuantity());

        return new PlaceOrderResponse(orderNo, "订单已提交，正在处理中");
    }

    public Optional<Order> getOrder(String orderNo) {
        return orderRepo.findByOrderNo(orderNo);
    }

    public List<Order> getAllOrders() {
        return orderRepo.findAll();
    }
}
