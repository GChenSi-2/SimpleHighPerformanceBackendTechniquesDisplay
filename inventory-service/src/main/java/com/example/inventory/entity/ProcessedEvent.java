package com.example.inventory.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 技术点3: 幂等表
 * 消费端通过 INSERT IGNORE 写入此表，若已存在则跳过处理。
 * 保证重复消息不会重复扣库存。
 */
@Data
@Entity
@Table(name = "t_processed_event")
public class ProcessedEvent {
    @Id
    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
