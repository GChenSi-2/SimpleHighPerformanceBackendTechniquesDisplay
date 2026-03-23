package com.example.inventory.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 技术点2: Outbox 发件箱表
 * 在同一个 MySQL 事务内写业务表 + Outbox 表，保证原子性。
 * 后台定时任务扫描 status=0 的记录，发送到 Kafka 后标记为已发布。
 */
@Data
@Entity
@Table(name = "t_outbox")
public class Outbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** 0=待发布 1=已发布 */
    @Column(nullable = false, columnDefinition = "TINYINT")
    private Integer status;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
