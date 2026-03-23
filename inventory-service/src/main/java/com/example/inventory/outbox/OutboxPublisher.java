package com.example.inventory.outbox;

import com.example.inventory.entity.Outbox;
import com.example.inventory.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 技术点2: Outbox 后台发布者
 *
 * 定时扫描 t_outbox 中 status=0（待发布）的记录，
 * 发送到 Kafka 后标记为 status=1（已发布）。
 *
 * 这样即使应用在事务提交后、消息发送前崩溃，
 * 下次启动时仍会重新发布未发布的事件 → 最终一致性。
 *
 * 注意：下游消费者需要幂等（技术点3已保证），因为 outbox 可能重复发布。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private static final String OUTBOX_TOPIC = "outbox-events";

    /** 每5秒扫描一次待发布的 outbox 记录 */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        List<Outbox> pendingEvents = outboxRepo.findTop100ByStatusOrderByIdAsc(0);
        if (pendingEvents.isEmpty()) {
            return;
        }

        for (Outbox event : pendingEvents) {
            try {
                // 发送到 Kafka，key = aggregateId（如 orderNo）
                kafkaTemplate.send(OUTBOX_TOPIC, event.getAggregateId(), event.getPayload()).get();

                // 标记为已发布
                event.setStatus(1);
                outboxRepo.save(event);

                log.info("[Outbox] 已发布事件 id={}, type={}, aggregateId={}",
                        event.getId(), event.getEventType(), event.getAggregateId());
            } catch (Exception e) {
                log.error("[Outbox] 发布失败 id={}, 将在下次重试", event.getId(), e);
                // 失败的不标记，下次扫描会重试
                break;
            }
        }
    }
}
