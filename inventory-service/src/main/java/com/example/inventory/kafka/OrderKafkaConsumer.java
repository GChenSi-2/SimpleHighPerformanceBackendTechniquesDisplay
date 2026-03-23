package com.example.inventory.kafka;

import com.example.inventory.service.OrderProcessingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/* *
 * 技术点1: 削峰填谷 —— 异步消费 Kafka 消息
 * 技术点3: 幂等 —— 消费前先检查 t_processed_event
 * 技术点4: 分区顺序 —— 同一 skuId 在同一分区，保证顺序消费
 *
 * Kafka 重试策略：
 * - 可重试异常（如 DB 超时）→ 发到 retry topic，最多重试3次
 * - 不可重试异常（如库存不足）→ 直接标记失败，不重试
 * - 超过重试次数 → 发到 DLQ（死信队列）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderKafkaConsumer {

    private static final int MAX_RETRY = 3;

    private final OrderProcessingService orderProcessingService;
    private final OrderKafkaProducer producer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 监听主 topic */
    @KafkaListener(topics = "${app.kafka.order-topic}", groupId = "inventory-group")
    public void consumeOrderEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        processRecord(record, ack);
    }

    /** 监听重试 topic（相同处理逻辑） */
    @KafkaListener(topics = "${app.kafka.retry-topic}", groupId = "inventory-group")
    public void consumeRetryEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        processRecord(record, ack);
    }

    private void processRecord(ConsumerRecord<String, String> record, Acknowledgment ack) {
        OrderEvent event = null;
        try {
            event = objectMapper.readValue(record.value(), OrderEvent.class);
            log.info("[Consumer] 收到消息 orderNo={}, partition={}, offset={}",
                    event.getOrderNo(), record.partition(), record.offset());

            orderProcessingService.processOrder(event);

            // 手动提交 offset（enable-auto-commit=false）
            ack.acknowledge();

        } catch (InsufficientStockException e) {
            // 库存不足 → 不可重试，直接标记失败
            log.warn("[Consumer] 库存不足，不重试 orderNo={}", event != null ? event.getOrderNo() : "unknown");
            ack.acknowledge();

        } catch (Exception e) {
            log.error("[Consumer] 处理失败 orderNo={}", event != null ? event.getOrderNo() : "unknown", e);
            if (event != null && event.getRetryCount() < MAX_RETRY) {
                // 可重试 → 发到 retry topic
                event.setRetryCount(event.getRetryCount() + 1);
                producer.sendToRetry(event);
            } else if (event != null) {
                // 超过最大重试 → 发到 DLQ
                producer.sendToDlq(event);
            }
            ack.acknowledge();
        }
    }
}
