package com.example.inventory.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 技术点1: 削峰填谷 —— 写请求先进 Kafka
 *
 * 下单请求不直接操作 DB，而是发送消息到 Kafka。
 * key = skuId，保证同一 SKU 的订单落在同一分区，顺序消费（技术点4）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderKafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.kafka.order-topic}")
    private String orderTopic;

    @Value("${app.kafka.retry-topic}")
    private String retryTopic;

    @Value("${app.kafka.dlq-topic}")
    private String dlqTopic;

    /**
     * 发送下单事件到主 topic
     * key = skuId → 同一 SKU 落在同一分区 → 顺序处理
     */
    public void sendOrderEvent(OrderEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            // key = skuId，Kafka 按 key hash 分配分区
            kafkaTemplate.send(orderTopic, event.getSkuId(), json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[Kafka] 发送失败 orderNo={}, error={}", event.getOrderNo(), ex.getMessage());
                        } else {
                            log.info("[Kafka] 发送成功 orderNo={}, partition={}",
                                    event.getOrderNo(), result.getRecordMetadata().partition());
                        }
                    });
        } catch (Exception e) {
            log.error("[Kafka] 序列化失败 orderNo={}", event.getOrderNo(), e);
            throw new RuntimeException("Failed to send order event", e);
        }
    }

    /** 发送到重试 topic */
    public void sendToRetry(OrderEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(retryTopic, event.getSkuId(), json);
            log.warn("[Kafka] 发送到重试队列 orderNo={}, retryCount={}", event.getOrderNo(), event.getRetryCount());
        } catch (Exception e) {
            log.error("[Kafka] 发送重试队列失败", e);
        }
    }

    /** 发送到死信 topic（人工处理） */
    public void sendToDlq(OrderEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(dlqTopic, event.getSkuId(), json);
            log.error("[Kafka] 发送到死信队列 orderNo={}", event.getOrderNo());
        } catch (Exception e) {
            log.error("[Kafka] 发送死信队列失败", e);
        }
    }
}
