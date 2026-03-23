package com.example.inventory.repository;

import com.example.inventory.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    /**
     * 技术点3: 幂等 —— INSERT IGNORE
     * 如果 event_id 已存在，INSERT IGNORE 不会报错也不会插入，返回0
     * 返回1表示插入成功（首次处理），返回0表示已处理过
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO t_processed_event (event_id, processed_at) VALUES (:eventId, NOW())",
           nativeQuery = true)
    int insertIgnore(@Param("eventId") String eventId);
}
