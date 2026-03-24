<p align="center">
  <a href="./README_ZH.md"><img src="https://img.shields.io/badge/lang-中文-red?style=for-the-badge" alt="中文"></a>
  <a href="../README.md"><img src="https://img.shields.io/badge/lang-English-blue?style=for-the-badge" alt="English"></a>
  <a href="./README_JA.md"><img src="https://img.shields.io/badge/lang-日本語-green?style=for-the-badge" alt="日本語"></a>
</p>

<p align="center">
  <b>📖 详细工程指南 → <a href="./DETAILED_GUIDE.md">docs/DETAILED_GUIDE.md</a></b><br>
  <b>📋 技术 PRD → <a href="./TECHNICAL_PRD.md">docs/TECHNICAL_PRD.md</a></b>
</p>

---

# 商品库存秒杀系统 —— 后端技术点学习项目

一个极简的 Spring Boot 3 + React 项目，用 **商品下单扣库存** 这一个业务场景，覆盖 6 个核心后端技术点。

## 架构总览

```
┌──────────┐     POST /api/orders      ┌──────────────┐
│  React   │ ──────────────────────────→│  Spring Boot │
│ Frontend │                            │  Controller  │
└──────────┘                            └──────┬───────┘
                                               │
                              ┌────────────────↓────────────────┐
                              │         Kafka Producer           │
                              │   key=skuId → 同SKU同分区(顺序)  │
                              └────────────────┬────────────────┘
                                               │
                              ┌────────────────↓────────────────┐
                              │         Kafka Consumer           │
                              │  (异步消费，削峰填谷)              │
                              └────────────────┬────────────────┘
                                               │
                    ┌──────────────────────────────────────────────┐
                    │           MySQL 单事务内完成                    │
                    │                                              │
                    │  1. INSERT IGNORE t_processed_event (幂等)    │
                    │  2. UPDATE t_product SET stock=stock-1        │
                    │     WHERE stock>=1 (原子扣库存)                │
                    │  3. INSERT t_order (创建订单)                  │
                    │  4. INSERT t_outbox (Outbox事件)              │
                    └──────────────────────────────────────────────┘
                                               │
                              ┌────────────────↓────────────────┐
                              │     Outbox 后台发布 (@Scheduled)  │
                              │  扫描 status=0 → 发Kafka → 标1   │
                              └─────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────┐
  │  GET /api/products/{skuId}  商品详情 (读路径)                 │
  │                                                             │
  │  CompletableFuture.supplyAsync 并发发起3个查询:               │
  │    ① 商品信息 (Redis缓存 → 互斥锁回源 → TTL抖动)             │
  │    ② 实时库存 (直查DB)                                       │
  │    ③ 促销信息 (查DB)                                         │
  │  各自设超时，超时则降级返回默认值                                │
  └─────────────────────────────────────────────────────────────┘
```

## 6 个技术点对照表

| # | 技术点 | 文件位置 | 关键代码 |
|---|--------|---------|---------|
| 1 | **削峰填谷** (Kafka异步) | `OrderKafkaProducer.java` / `OrderKafkaConsumer.java` | 下单 → 发Kafka → 异步消费 |
| 2 | **Outbox最终一致** | `OrderProcessingService.java` / `OutboxPublisher.java` | 事务内写outbox，定时扫描发布 |
| 3 | **幂等** (INSERT IGNORE) | `ProcessedEventRepository.java` / `OrderProcessingService.java` | `INSERT IGNORE t_processed_event` |
| 4 | **原子扣库存 + 分区顺序** | `ProductRepository.java` / `OrderKafkaProducer.java` | `stock=stock-1 WHERE stock>=1` + key=skuId |
| 5 | **CompletableFuture并发读** | `ProductDetailService.java` | 3个异步查询 + 超时降级 |
| 6 | **Redis缓存** (防击穿/雪崩) | `ProductCacheService.java` | 互斥锁 + TTL随机抖动 + 空值缓存 |

**扩展点：** Kafka 重试 topic + DLQ 死信队列见 `OrderKafkaConsumer.java`

## 快速启动

### 1. 启动依赖 (MySQL + Redis + Kafka)

```bash
docker-compose up -d
```

### 2. 启动后端

```bash
cd inventory-service
mvn spring-boot:run
```

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173

## 项目结构

```
BackendExample/
├── docker-compose.yml          # 一键启动 MySQL/Redis/Kafka
├── sql/schema.sql              # 数据库建表 + 测试数据
├── inventory-service/          # Spring Boot 后端
│   └── src/main/java/com/example/inventory/
│       ├── config/             # Kafka Topic、Redis、异步线程池、CORS配置
│       ├── entity/             # JPA实体：Product, Order, Outbox, ProcessedEvent, Promotion
│       ├── repository/         # 数据访问层（含原子扣库存SQL、INSERT IGNORE）
│       ├── kafka/              # Kafka 生产者/消费者/事件模型
│       ├── outbox/             # Outbox 后台发布定时任务
│       ├── service/            # 业务逻辑层
│       │   ├── OrderService.java           # 下单 → 发Kafka
│       │   ├── OrderProcessingService.java # Kafka消费端核心(幂等+扣库存+outbox)
│       │   ├── ProductCacheService.java    # Redis缓存(互斥锁+TTL抖动)
│       │   ├── ProductDetailService.java   # CompletableFuture并发聚合
│       │   └── ProductService.java         # 商品CRUD
│       └── controller/         # REST API
└── frontend/                   # React + Vite 前端
    └── src/
        ├── api/index.js        # API调用封装
        └── components/         # 商品列表、详情、订单列表
```

## 面试扩展问题

### Kafka 重试与 DLQ
- **可重试异常**（DB超时等）→ 发到 `order-events-retry` topic，最多重试3次
- **不可重试异常**（库存不足）→ 直接标记失败，不进重试队列
- **超过重试次数** → 发到 `order-events-dlq` 死信队列，人工介入

### Topic 分区数选择
- 本项目配 **12分区**，适合中等规模（消费者数 ≤ 分区数）
- 生产环境常用 **12/24/48**，取决于消费端并发能力
- **热点SKU瓶颈**：单个热点SKU所有消息落在同一分区，可通过「分区内再分桶」或「热点SKU独立topic」解决
