package com.example.inventory.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Topic 配置
 * 技术点1: 分区数设为12，key=skuId 保证同一SKU的消息落在同一分区，顺序处理
 * 技术点(扩展): 配置 retry topic 和 DLQ topic
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${app.kafka.order-topic}")
    private String orderTopic;

    @Value("${app.kafka.order-topic-partitions}")
    private int partitions;

    @Value("${app.kafka.retry-topic}")
    private String retryTopic;

    @Value("${app.kafka.dlq-topic}")
    private String dlqTopic;

    /** 主 topic：12分区，3副本(生产用；本地开发可改为1) */
    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(orderTopic)
                .partitions(partitions)
                .replicas(1) // 本地开发用1，生产改为3
                .build();
    }

    /** 重试 topic：消费失败的消息转到这里，延迟后重新消费 */
    @Bean
    public NewTopic retryTopic() {
        return TopicBuilder.name(retryTopic)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    /** 死信 topic：重试多次仍失败的消息，人工介入处理 */
    @Bean
    public NewTopic dlqTopic() {
        return TopicBuilder.name(dlqTopic)
                .partitions(partitions)
                .replicas(1)
                .build();
    }
}
