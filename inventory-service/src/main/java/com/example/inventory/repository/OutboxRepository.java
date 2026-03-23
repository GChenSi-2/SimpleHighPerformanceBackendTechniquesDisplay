package com.example.inventory.repository;

import com.example.inventory.entity.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {
    /** 查找待发布的 outbox 记录，批量发送 */
    List<Outbox> findTop100ByStatusOrderByIdAsc(int status);
}
