# Technical PRD: Inventory Flash-Sale System
# 技术产品需求文档：商品库存秒杀系统

---

## Table of Contents / 目录

1. [System Overview / 系统概述](#1-system-overview--系统概述)
2. [Architecture Overview / 架构总览](#2-architecture-overview--架构总览)
3. [Tech Stack / 技术栈](#3-tech-stack--技术栈)
4. [Feature 1: Peak Shaving — Kafka Async Write / 功能1：削峰填谷 — Kafka异步写入](#4-feature-1-peak-shaving--kafka-async-write--功能1削峰填谷--kafka异步写入)
5. [Feature 2: Outbox Pattern — Eventual Consistency / 功能2：Outbox模式 — 最终一致性](#5-feature-2-outbox-pattern--eventual-consistency--功能2outbox模式--最终一致性)
6. [Feature 3: Idempotency — Deduplication / 功能3：幂等性 — 消息去重](#6-feature-3-idempotency--deduplication--功能3幂等性--消息去重)
7. [Feature 4: Atomic Inventory Deduction / 功能4：原子库存扣减](#7-feature-4-atomic-inventory-deduction--功能4原子库存扣减)
8. [Feature 5: Concurrent Read Aggregation / 功能5：并发读聚合](#8-feature-5-concurrent-read-aggregation--功能5并发读聚合)
9. [Feature 6: Redis Cache Strategy / 功能6：Redis缓存策略](#9-feature-6-redis-cache-strategy--功能6redis缓存策略)
10. [Feature 7: Kafka Retry & DLQ / 功能7：Kafka重试与死信队列](#10-feature-7-kafka-retry--dlq--功能7kafka重试与死信队列)
11. [Database Design / 数据库设计](#11-database-design--数据库设计)
12. [API Specification / API接口规范](#12-api-specification--api接口规范)
13. [End-to-End Data Flow / 端到端数据流](#13-end-to-end-data-flow--端到端数据流)
14. [Failure Scenarios & Handling / 故障场景与处理](#14-failure-scenarios--handling--故障场景与处理)
15. [日本語版 (Japanese Translation)](#15-日本語版-japanese-translation)

---

## 1. System Overview / 系统概述

### English

This system is a **minimal flash-sale inventory service** designed as a learning project. It implements a single business scenario — **browse products → view details → place an order** — while covering 6 core backend engineering techniques that improve system **performance**, **reliability**, and **data consistency** under high concurrency.

**Goals:**
- Demonstrate how each technique solves a specific real-world problem
- Keep business logic as simple as possible so the focus stays on infrastructure patterns
- Provide a runnable codebase with Docker Compose for local development

### 中文

本系统是一个**极简秒杀库存服务**，作为学习项目设计。它实现一个单一业务场景——**浏览商品 → 查看详情 → 下单购买**——同时覆盖6个核心后端工程技术，在高并发场景下提升系统的**性能**、**可靠性**和**数据一致性**。

**目标：**
- 演示每个技术如何解决特定的实际问题
- 保持业务逻辑尽可能简单，使学习重点聚焦在基础设施模式上
- 提供可运行的代码库，通过 Docker Compose 实现本地一键启动

---

## 2. Architecture Overview / 架构总览

### System Architecture Diagram / 系统架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CLIENT (React + Vite)                           │
│                                                                         │
│   ┌──────────────┐   ┌──────────────────┐   ┌───────────────────┐      │
│   │ Product List  │   │ Product Detail    │   │ Order List        │      │
│   │ 商品列表页    │   │ 商品详情页        │   │ 订单列表页        │      │
│   └──────┬───────┘   └────────┬─────────┘   └────────┬──────────┘      │
│          │                    │                       │                  │
└──────────┼────────────────────┼───────────────────────┼──────────────────┘
           │ GET /api/products  │ GET /api/products/:id │ GET/POST /api/orders
           ▼                    ▼                       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     SPRING BOOT APPLICATION                             │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                    REST Controllers                              │    │
│  │                    (ProductController, OrderController)           │    │
│  └────────┬──────────────────┬──────────────────────┬──────────────┘    │
│           │                  │                      │                    │
│  ┌────────▼────────┐ ┌──────▼───────────┐ ┌────────▼──────────┐        │
│  │ ProductService   │ │ ProductDetail    │ │ OrderService       │        │
│  │ (CRUD)           │ │ Service          │ │ (Place Order)      │        │
│  │                  │ │ (CompleteFuture) │ │                    │        │
│  └────────┬─────────┘ └──────┬──────────┘ └────────┬───────────┘        │
│           │                  │                      │                    │
│  ┌────────▼─────────┐       │              ┌────────▼──────────┐        │
│  │ ProductCache      │       │              │ Kafka Producer    │        │
│  │ Service (Redis)   │◄──────┘              │ (key=skuId)       │        │
│  └────────┬─────────┘                      └────────┬───────────┘        │
│           │                                         │                    │
└───────────┼─────────────────────────────────────────┼────────────────────┘
            │                                         │
            ▼                                         ▼
┌───────────────────┐                    ┌────────────────────────┐
│                   │                    │                        │
│   Redis Cache     │                    │   Kafka Cluster        │
│   (Hot data,      │                    │   (12 partitions,      │
│    Mutex lock,    │                    │    key=skuId routing)  │
│    TTL jitter)    │                    │                        │
│                   │                    │   ┌──────────────────┐ │
│   技术点6:        │                    │   │ order-events     │ │
│   缓存防击穿/雪崩 │                    │   │ order-events-retry│ │
└───────────────────┘                    │   │ order-events-dlq │ │
                                         │   └──────────────────┘ │
                                         └───────────┬────────────┘
                                                     │
                                                     ▼
                                         ┌────────────────────────┐
                                         │   Kafka Consumer       │
                                         │   (Async processing)   │
                                         └───────────┬────────────┘
                                                     │
                                                     ▼
                              ┌──────────────────────────────────────────┐
                              │        MySQL — Single Transaction        │
                              │        MySQL — 单个事务内完成             │
                              │                                          │
                              │  Step 1: INSERT IGNORE t_processed_event │
                              │          (Idempotency / 幂等)            │
                              │                                          │
                              │  Step 2: UPDATE t_product                │
                              │          SET stock = stock - qty         │
                              │          WHERE stock >= qty              │
                              │          (Atomic deduction / 原子扣减)   │
                              │                                          │
                              │  Step 3: INSERT t_order                  │
                              │          (Create order / 创建订单)       │
                              │                                          │
                              │  Step 4: INSERT t_outbox                 │
                              │          (Outbox event / 事件发件箱)     │
                              └──────────────────┬───────────────────────┘
                                                 │
                                                 ▼
                              ┌──────────────────────────────────────────┐
                              │   Outbox Publisher (@Scheduled 5s)       │
                              │   Scan status=0 → Publish to Kafka      │
                              │   → Mark status=1                        │
                              │                                          │
                              │   后台定时扫描 → 发布到Kafka → 标记已发布 │
                              └──────────────────────────────────────────┘
```

---

## 3. Tech Stack / 技术栈

| Layer / 层 | Technology / 技术 | Purpose / 用途 |
|---|---|---|
| Frontend / 前端 | React 18 + Vite + React Router | SPA, product browsing & ordering UI / 单页应用，商品浏览与下单界面 |
| Backend / 后端 | Spring Boot 3.2 + JPA + Lombok | REST API, business logic / REST接口，业务逻辑 |
| Message Queue / 消息队列 | Apache Kafka 3.7 (KRaft) | Async write buffer, event streaming / 异步写入缓冲，事件流 |
| Database / 数据库 | MySQL 8.0 | Persistent storage, transactional writes / 持久化存储，事务写入 |
| Cache / 缓存 | Redis 7 | Hot data caching, distributed lock / 热点数据缓存，分布式锁 |
| Infra / 基础设施 | Docker Compose | One-click local environment / 一键本地环境 |

---

## 4. Feature 1: Peak Shaving — Kafka Async Write / 功能1：削峰填谷 — Kafka异步写入

### Problem / 问题

> **EN:** In a flash-sale scenario, thousands of order requests hit the server simultaneously. If each request directly writes to MySQL, the database connection pool is exhausted and the system crashes.
>
> **CN:** 在秒杀场景中，数千个下单请求同时到达服务器。如果每个请求都直接写MySQL，数据库连接池会被耗尽，系统崩溃。

### Solution / 解决方案

```
  Without Kafka (直接写DB):                    With Kafka (经过Kafka):
  没有Kafka:                                    有Kafka:

  ┌──────┐  1000 req/s   ┌───────┐             ┌──────┐  1000 req/s  ┌───────┐
  │Client├───────────────→│ MySQL │ ← CRASH!    │Client├─────────────→│ Kafka │
  └──────┘                └───────┘             └──────┘              └───┬───┘
                                                                         │
                                                              50 msg/s   │ Controlled
                                                              (可控速率)  │ consumption
                                                                         ▼
                                                                    ┌───────┐
                                                                    │ MySQL │ ← Stable!
                                                                    └───────┘
```

### Business Logic / 业务逻辑

**EN:**
1. User clicks "Place Order" → Frontend sends `POST /api/orders` with `{skuId, quantity}`
2. `OrderService` generates a unique `orderNo` and `eventId`
3. Instead of writing to DB, it **publishes an `OrderEvent` to Kafka** topic `order-events`
4. The Kafka message key is set to `skuId` — this ensures all orders for the same product are routed to the **same partition** (enabling sequential processing per SKU)
5. The API **returns immediately** with the `orderNo` and a message "Order submitted, processing..."
6. On the consumer side, the `OrderKafkaConsumer` pulls messages at a controlled rate and processes them

**CN:**
1. 用户点击"立即下单" → 前端发送 `POST /api/orders`，携带 `{skuId, quantity}`
2. `OrderService` 生成唯一的 `orderNo` 和 `eventId`
3. 不直接写DB，而是**将 `OrderEvent` 发布到 Kafka** 的 `order-events` topic
4. Kafka 消息的 key 设为 `skuId` —— 这确保同一商品的所有订单路由到**同一分区**（实现按SKU顺序处理）
5. API **立即返回**订单号和提示"订单已提交，正在处理中"
6. 消费端的 `OrderKafkaConsumer` 按可控速率拉取消息并处理

### Data Flow Diagram / 数据流图

```
  User clicks          OrderService              Kafka                Consumer           MySQL
  "Place Order"        (API Layer)               Broker               (Async)
  用户点击下单          (接口层)
       │                    │                       │                    │                  │
       │  POST /orders      │                       │                    │                  │
       ├───────────────────→│                       │                    │                  │
       │                    │                       │                    │                  │
       │                    │  send(topic,          │                    │                  │
       │                    │    key=skuId,          │                    │                  │
       │                    │    value=OrderEvent)   │                    │                  │
       │                    ├──────────────────────→│                    │                  │
       │                    │                       │  (buffered)        │                  │
       │  200 OK            │                       │  (缓冲中)          │                  │
       │  {orderNo, msg}    │                       │                    │                  │
       │◄───────────────────┤                       │                    │                  │
       │                    │                       │                    │                  │
       │  (User sees        │                       │  poll()            │                  │
       │   "Processing...")  │                       │◄───────────────────┤                  │
       │  (用户看到          │                       │                    │                  │
       │   "处理中...")      │                       │  OrderEvent        │                  │
       │                    │                       ├───────────────────→│                  │
       │                    │                       │                    │                  │
       │                    │                       │                    │  Transaction:    │
       │                    │                       │                    │  INSERT/UPDATE   │
       │                    │                       │                    ├─────────────────→│
       │                    │                       │                    │                  │
       │                    │                       │                    │  ack()           │
       │                    │                       │◄───────────────────┤                  │
```

### Key Source Files / 关键源文件

| File / 文件 | Role / 职责 |
|---|---|
| `kafka/OrderKafkaProducer.java` | Sends order event to Kafka with key=skuId / 以skuId为key发送订单事件到Kafka |
| `kafka/OrderKafkaConsumer.java` | Consumes messages with manual ack / 手动确认模式消费消息 |
| `kafka/OrderEvent.java` | Event payload (eventId, orderNo, skuId, qty, retryCount) / 事件消息体 |
| `service/OrderService.java` | Entry point: generates orderNo, sends to Kafka / 入口：生成订单号，发送Kafka |
| `config/KafkaTopicConfig.java` | Creates topics with 12 partitions / 创建12分区的topic |

### Why 12 Partitions? / 为什么选12个分区？

> **EN:** 12 is divisible by 1, 2, 3, 4, 6, and 12 — making it easy to scale consumers. If you have 4 consumer instances, each gets 3 partitions. If you scale to 6, each gets 2. Production systems commonly use 12, 24, or 48.
>
> **CN:** 12可以被1、2、3、4、6、12整除——方便扩展消费者数量。4个消费实例时每个分3个分区，扩到6个时每个分2个。生产环境常用12、24或48。

---

## 5. Feature 2: Outbox Pattern — Eventual Consistency / 功能2：Outbox模式 — 最终一致性

### Problem / 问题

> **EN:** After deducting inventory and creating an order, we need to notify downstream systems (e.g., notification service, analytics). If we publish to Kafka after the DB commit, and the app crashes between commit and publish, the event is lost. If we publish before commit, and the transaction rolls back, we've sent a phantom event.
>
> **CN:** 扣完库存、创建订单后，我们需要通知下游系统（如通知服务、数据分析）。如果在DB提交后发Kafka，应用在提交和发布之间崩溃，事件就丢失了。如果在提交前发布，事务回滚了，就发了一个幽灵事件。

### Solution: Transactional Outbox / 解决方案：事务性发件箱

```
  ╔══════════════════════════════════════════════════════════════╗
  ║                                                              ║
  ║   ┌──────────────── MySQL Transaction ─────────────────┐     ║
  ║   │                  MySQL 事务                         │     ║
  ║   │                                                     │     ║
  ║   │   ① UPDATE t_product (deduct stock / 扣库存)       │     ║
  ║   │   ② INSERT t_order   (create order / 创建订单)     │     ║
  ║   │   ③ INSERT t_outbox  (save event  / 存事件)        │     ║
  ║   │                                                     │     ║
  ║   │   → All 3 succeed or all 3 rollback                 │     ║
  ║   │   → 三者要么全部成功，要么全部回滚                    │     ║
  ║   │                                                     │     ║
  ║   └─────────────────────────────────────────────────────┘     ║
  ║                                                              ║
  ║   ┌──────────────── Background Job (@Scheduled 5s) ────┐     ║
  ║   │                  后台定时任务（每5秒）                │     ║
  ║   │                                                     │     ║
  ║   │   SELECT * FROM t_outbox WHERE status = 0           │     ║
  ║   │              │                                      │     ║
  ║   │              ▼                                      │     ║
  ║   │   kafkaTemplate.send(topic, payload)                │     ║
  ║   │              │                                      │     ║
  ║   │              ▼                                      │     ║
  ║   │   UPDATE t_outbox SET status = 1 WHERE id = ?      │     ║
  ║   │                                                     │     ║
  ║   └─────────────────────────────────────────────────────┘     ║
  ║                                                              ║
  ╚══════════════════════════════════════════════════════════════╝
```

### Business Logic / 业务逻辑

**EN:**
1. Inside `OrderProcessingService.processOrder()` — a single `@Transactional` method:
   - Deduct inventory (atomic SQL)
   - Create the order record
   - **Insert an Outbox row** with `status=0` (pending), containing the serialized order event as JSON payload
2. All three writes are in ONE transaction — they atomically succeed or fail together
3. `OutboxPublisher` runs every 5 seconds via `@Scheduled`:
   - Queries `t_outbox WHERE status=0 LIMIT 100`
   - For each pending event, sends it to Kafka
   - On successful send, updates `status=1` (published)
   - On failure, breaks the loop — will retry next cycle
4. Even if the app crashes after the transaction but before Outbox publish, the event persists in MySQL and will be published on next startup

**CN:**
1. 在 `OrderProcessingService.processOrder()` 中——一个 `@Transactional` 方法：
   - 扣减库存（原子SQL）
   - 创建订单记录
   - **插入 Outbox 行**，`status=0`（待发布），payload 是序列化的订单事件JSON
2. 三次写入在同一个事务中——原子性地一起成功或一起回滚
3. `OutboxPublisher` 通过 `@Scheduled` 每5秒执行一次：
   - 查询 `t_outbox WHERE status=0 LIMIT 100`
   - 对每条待发布事件，发送到 Kafka
   - 发送成功后更新 `status=1`（已发布）
   - 发送失败则中断循环——下次周期重试
4. 即使应用在事务提交后、Outbox 发布前崩溃，事件仍持久化在 MySQL 中，下次启动时会继续发布

### Failure Analysis / 故障分析

```
  Scenario A: App crashes AFTER transaction, BEFORE outbox publish
  场景A: 应用在事务提交后、outbox发布前崩溃

  ┌──────────┐   ┌─────────┐   ┌───────┐
  │ App      │   │ MySQL   │   │ Kafka │
  │ crashes! │   │ ✓ order │   │ ✗ no  │   ← Event NOT lost! Outbox row exists.
  │ 崩溃!    │   │ ✓ outbox│   │   msg │      事件没丢！Outbox行还在。
  └──────────┘   │ (status │   └───────┘
                 │   =0)   │
                 └─────────┘
                       │
      After restart    │  OutboxPublisher scans status=0
      重启后           ▼  OutboxPublisher 扫描 status=0
                 ┌─────────┐   ┌───────┐
                 │ MySQL   │   │ Kafka │
                 │ outbox  ├──→│ ✓ msg │   ← Event delivered on next cycle!
                 │ status→1│   │ sent  │      下次周期事件被投递！
                 └─────────┘   └───────┘

  Scenario B: Transaction rolls back
  场景B: 事务回滚

  ┌──────────┐   ┌─────────┐   ┌───────┐
  │ App      │   │ MySQL   │   │ Kafka │
  │ rollback │   │ ✗ no    │   │ ✗ no  │   ← Consistent! No phantom event.
  │ 回滚     │   │   data  │   │   msg │      一致！没有幽灵事件。
  └──────────┘   └─────────┘   └───────┘
```

### Key Source Files / 关键源文件

| File / 文件 | Role / 职责 |
|---|---|
| `service/OrderProcessingService.java` | Writes order + outbox in one transaction / 一个事务内写订单+outbox |
| `outbox/OutboxPublisher.java` | Scans & publishes pending outbox events every 5s / 每5秒扫描发布待处理事件 |
| `entity/Outbox.java` | Outbox table entity / Outbox表实体 |
| `repository/OutboxRepository.java` | `findTop100ByStatusOrderByIdAsc(0)` |

---

## 6. Feature 3: Idempotency — Deduplication / 功能3：幂等性 — 消息去重

### Problem / 问题

> **EN:** Kafka guarantees **at-least-once delivery** — the same message may be delivered multiple times (e.g., consumer crash before ack, network partition, Outbox re-publish). Without deduplication, the same order could deduct inventory twice.
>
> **CN:** Kafka 保证**至少一次投递**——同一条消息可能被多次投递（如消费者在ack前崩溃、网络分区、Outbox重复发布）。如果不去重，同一笔订单可能重复扣库存。

### Solution: INSERT IGNORE / 解决方案：INSERT IGNORE

```
  Message arrives (first time)          Message arrives (duplicate)
  消息首次到达                           消息重复到达

  ┌─────────────────────────┐           ┌─────────────────────────┐
  │ INSERT IGNORE INTO      │           │ INSERT IGNORE INTO      │
  │ t_processed_event       │           │ t_processed_event       │
  │ (event_id) VALUES (?)   │           │ (event_id) VALUES (?)   │
  │                         │           │                         │
  │ Result: 1 row affected  │           │ Result: 0 rows affected │
  │ 结果: 影响1行            │           │ 结果: 影响0行            │
  │                         │           │                         │
  │     → PROCEED           │           │     → SKIP (return)     │
  │     → 继续处理           │           │     → 跳过（直接返回）   │
  └─────────────────────────┘           └─────────────────────────┘
```

### Business Logic / 业务逻辑

**EN:**
1. Every `OrderEvent` carries a unique `eventId` (UUID)
2. At the very beginning of `OrderProcessingService.processOrder()`, before any business logic:
   - Execute `INSERT IGNORE INTO t_processed_event (event_id) VALUES (?)`
   - `INSERT IGNORE` is a MySQL-specific syntax: if the primary key already exists, it silently does nothing and returns 0 affected rows
3. If `affected rows == 0` → this event was already processed → **skip immediately**
4. If `affected rows == 1` → first time seeing this event → proceed with inventory deduction
5. Because this INSERT is part of the same transaction as the business writes, it's atomic: if the transaction rolls back, the processed_event row is also rolled back, allowing re-processing on next delivery

**CN:**
1. 每个 `OrderEvent` 携带唯一的 `eventId`（UUID）
2. 在 `OrderProcessingService.processOrder()` 的最开始，任何业务逻辑之前：
   - 执行 `INSERT IGNORE INTO t_processed_event (event_id) VALUES (?)`
   - `INSERT IGNORE` 是 MySQL 特有语法：如果主键已存在，静默不插入，返回影响行数0
3. 如果 `影响行数 == 0` → 该事件已被处理过 → **直接跳过**
4. 如果 `影响行数 == 1` → 首次处理该事件 → 继续扣库存
5. 因为这个 INSERT 和业务写入在同一事务中，它是原子的：如果事务回滚，processed_event 行也回滚，允许下次投递时重新处理

### Key Source Files / 关键源文件

| File / 文件 | Role / 职责 |
|---|---|
| `repository/ProcessedEventRepository.java` | Native query: `INSERT IGNORE INTO t_processed_event` |
| `service/OrderProcessingService.java` | Calls `insertIgnore()` as first step in transaction / 事务第一步调用幂等检查 |
| `entity/ProcessedEvent.java` | Entity with `event_id` as primary key / 以event_id为主键的实体 |

---

## 7. Feature 4: Atomic Inventory Deduction / 功能4：原子库存扣减

### Problem / 问题

> **EN:** Under concurrency, naive read-then-write stock deduction causes overselling. Thread A reads stock=1, Thread B reads stock=1, both think there's enough stock, both write stock=0 — but only 1 item existed.
>
> **CN:** 并发场景下，先读后写的库存扣减会导致超卖。线程A读库存=1，线程B也读库存=1，两者都认为库存充足，都写库存=0——但实际只有1件商品。

### Solution: Atomic SQL + Kafka Partition Ordering / 解决方案：原子SQL + Kafka分区顺序

```
  ╔════════════════════════════════════════════════════════════╗
  ║  Atomic SQL (Single Statement, Row-Level Lock)             ║
  ║  原子SQL（单条语句，行级锁）                                ║
  ║                                                            ║
  ║  UPDATE t_product                                          ║
  ║  SET stock = stock - #{qty}                                ║
  ║  WHERE sku_id = #{skuId}                                   ║
  ║    AND stock >= #{qty}     ← Guard against overselling     ║
  ║                            ← 防止超卖的守卫条件              ║
  ║                                                            ║
  ║  Affected rows = 1  →  Success (deducted / 扣减成功)       ║
  ║  Affected rows = 0  →  Insufficient stock (库存不足)       ║
  ╚════════════════════════════════════════════════════════════╝
```

### Kafka Partition Ordering / Kafka分区顺序保证

```
  Orders for different SKUs → Different partitions → Parallel processing
  不同SKU的订单           → 不同分区             → 并行处理

  ┌─────────────┐     Partition 0: [SKU001-ord1] [SKU001-ord2] [SKU001-ord3]
  │ Kafka Topic │     Partition 1: [SKU002-ord1] [SKU002-ord2]
  │ (12 parts)  │     Partition 2: [SKU003-ord1]
  │             │     Partition 3: (empty)
  │ key=skuId   │     ...
  │ hash分区    │     Partition 11: (empty)
  └─────────────┘

  Within each partition, messages are consumed IN ORDER.
  每个分区内的消息按顺序消费。

  → SKU001's orders processed one by one: ord1 → ord2 → ord3
  → SKU001的订单逐个处理: ord1 → ord2 → ord3

  → SKU002 processed independently in parallel
  → SKU002 在另一个分区独立并行处理
```

### Why Both? / 为什么两者都需要？

> **EN:** The atomic SQL alone prevents overselling, but Kafka partition ordering provides an additional guarantee: orders for the same SKU are processed sequentially, which simplifies reasoning about consistency and eliminates lock contention on the same row.
>
> **CN:** 单独的原子SQL就能防止超卖，但Kafka分区顺序提供了额外保证：同一SKU的订单顺序处理，简化了一致性推理，消除了同一行上的锁竞争。

### Key Source Files / 关键源文件

| File / 文件 | Role / 职责 |
|---|---|
| `repository/ProductRepository.java` | `@Query("UPDATE ... SET stock=stock-:qty WHERE stock>=:qty")` |
| `kafka/OrderKafkaProducer.java` | `kafkaTemplate.send(topic, skuId, json)` — key=skuId for partition routing / key=skuId实现分区路由 |

---

## 8. Feature 5: Concurrent Read Aggregation / 功能5：并发读聚合

### Problem / 问题

> **EN:** A product detail page needs data from 3 sources. If queried sequentially (each taking ~100ms), total latency = 300ms. Under load, this adds up and degrades user experience.
>
> **CN:** 商品详情页需要从3个数据源获取数据。如果顺序查询（每个约100ms），总延迟=300ms。高负载下累积起来，严重影响用户体验。

### Solution: CompletableFuture + Timeout Degradation / 解决方案：CompletableFuture + 超时降级

```
  Sequential (顺序查询):                    Concurrent (并发查询):
  Total: ~300ms                            Total: ~max(100,100,100) ≈ 100ms

  ┌─────────┐  ┌─────────┐  ┌─────────┐   ┌─────────┐
  │ Query 1 │→ │ Query 2 │→ │ Query 3 │   │ Query 1 │──┐
  │ 100ms   │  │ 100ms   │  │ 100ms   │   │ 100ms   │  │
  └─────────┘  └─────────┘  └─────────┘   └─────────┘  │
  |──────────── 300ms ──────────────────|  ┌─────────┐  ├──→ Aggregate
  |                                     |  │ Query 2 │  │    聚合结果
                                           │ 100ms   │  │
                                           └─────────┘  │    Total ≈ 100ms
                                           ┌─────────┐  │
                                           │ Query 3 │──┘
                                           │ 100ms   │
                                           └─────────┘
```

### Business Logic / 业务逻辑

**EN:**
1. `ProductDetailService.getProductDetail(skuId)` fires 3 async queries simultaneously:
   - **Query 1:** Product basic info — reads from **Redis cache** (Feature 6) → `ProductCacheService.getBySkuId()`
   - **Query 2:** Realtime stock — reads **directly from MySQL** (not cached, to ensure accuracy)
   - **Query 3:** Active promotions — reads from MySQL `t_promotion` table
2. Each query runs in a thread pool (`queryExecutor`, 8 threads)
3. Each result is awaited with a **timeout**:
   - Product info: 500ms timeout
   - Realtime stock: 300ms timeout
   - Promotions: 300ms timeout
4. If any query **times out or fails**, the system **degrades gracefully**:
   - Product info timeout → return `"基本信息暂不可用"`
   - Stock timeout → fall back to cached stock value
   - Promotion timeout → return empty promotions list
5. The `degradeInfo` field in the response tells the frontend which data was degraded, and the UI shows a warning banner

**CN:**
1. `ProductDetailService.getProductDetail(skuId)` 同时发起3个异步查询：
   - **查询1：** 商品基本信息 —— 从 **Redis缓存** 读取（功能6）→ `ProductCacheService.getBySkuId()`
   - **查询2：** 实时库存 —— **直接查MySQL**（不走缓存，确保准确性）
   - **查询3：** 当前促销活动 —— 从MySQL的 `t_promotion` 表查询
2. 每个查询在线程池中执行（`queryExecutor`，8个线程）
3. 每个结果设置**超时等待**：
   - 商品信息：500ms 超时
   - 实时库存：300ms 超时
   - 促销信息：300ms 超时
4. 如果任一查询**超时或失败**，系统**优雅降级**：
   - 商品信息超时 → 返回 `"基本信息暂不可用"`
   - 库存超时 → 回退到缓存中的库存值
   - 促销超时 → 返回空促销列表
5. 响应中的 `degradeInfo` 字段告诉前端哪些数据被降级了，UI会显示警告横幅

### Timeout Degradation Flow / 超时降级流程

```
  ┌─────────────────────────────────────────────────────────────┐
  │                getProductDetail("SKU001")                    │
  │                                                             │
  │  CompletableFuture<Product>    ─── 500ms timeout ──┐        │
  │  CompletableFuture<Integer>    ─── 300ms timeout ──┤        │
  │  CompletableFuture<List<Promo>>─── 300ms timeout ──┘        │
  │                                                             │
  │  ┌──────────────────────────────────────────────────────┐   │
  │  │ try { result = future.get(timeout, MILLISECONDS) }   │   │
  │  │ catch (TimeoutException) {                           │   │
  │  │     → Use default/fallback value                     │   │
  │  │     → 使用默认/降级值                                  │   │
  │  │     → Add to degradeInfo map                         │   │
  │  │     → 添加到降级信息map                                │   │
  │  │ }                                                    │   │
  │  └──────────────────────────────────────────────────────┘   │
  │                                                             │
  │  Return ProductDetailVO {                                   │
  │      product:       (data or null),                         │
  │      realtimeStock: (actual or cached fallback),            │
  │      promotions:    (list or empty),                        │
  │      degradeInfo:   {"stock": "库存数据可能有延迟"} or null  │
  │  }                                                          │
  └─────────────────────────────────────────────────────────────┘
```

### Key Source Files / 关键源文件

| File / 文件 | Role / 职责 |
|---|---|
| `service/ProductDetailService.java` | Orchestrates 3 concurrent queries + timeout degradation / 编排3个并发查询+超时降级 |
| `config/AsyncConfig.java` | Thread pool for async queries (8 threads) / 异步查询线程池 |

---

## 9. Feature 6: Redis Cache Strategy / 功能6：Redis缓存策略

### Three Cache Problems Solved / 解决三大缓存问题

```
  ┌──────────────────────────────────────────────────────────────────┐
  │                     Cache Problems / 缓存问题                     │
  │                                                                  │
  │  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐ │
  │  │ Cache        │  │ Cache        │  │ Cache Avalanche        │ │
  │  │ Penetration  │  │ Breakdown    │  │ 缓存雪崩               │ │
  │  │ 缓存穿透     │  │ 缓存击穿     │  │                        │ │
  │  │              │  │              │  │ Many keys expire at    │ │
  │  │ Query for    │  │ Hot key      │  │ the same time → all   │ │
  │  │ non-existent │  │ expires →    │  │ requests hit DB        │ │
  │  │ data → every │  │ many threads │  │ simultaneously         │ │
  │  │ request hits │  │ hit DB at    │  │                        │ │
  │  │ DB           │  │ once         │  │ 大量key同时过期 →      │ │
  │  │              │  │              │  │ 所有请求同时打到DB      │ │
  │  │ 查不存在的   │  │ 热点key过期  │  │                        │ │
  │  │ 数据 → 每次  │  │ → 大量线程   │  │                        │ │
  │  │ 都打到DB     │  │ 同时打DB     │  │                        │ │
  │  └──────┬───────┘  └──────┬───────┘  └───────────┬────────────┘ │
  │         │                 │                      │              │
  │         ▼                 ▼                      ▼              │
  │  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐ │
  │  │ Solution:    │  │ Solution:    │  │ Solution:              │ │
  │  │ Null Value   │  │ Mutex Lock   │  │ TTL Jitter             │ │
  │  │ Cache        │  │ (SETNX)      │  │ TTL随机抖动            │ │
  │  │ 空值缓存     │  │ 互斥锁       │  │                        │ │
  │  │              │  │              │  │ TTL = base ± random    │ │
  │  │ Cache "NULL" │  │ Only 1       │  │ TTL = 300 ± rand(60)  │ │
  │  │ for 60s      │  │ thread loads │  │                        │ │
  │  │              │  │ from DB      │  │ Keys expire at         │ │
  │  │ 缓存空值     │  │              │  │ different times        │ │
  │  │ 60秒         │  │ 只有1个线程  │  │ 各key在不同时间过期     │ │
  │  │              │  │ 回源查DB     │  │                        │ │
  │  └──────────────┘  └──────────────┘  └────────────────────────┘ │
  └──────────────────────────────────────────────────────────────────┘
```

### Cache Read Flow / 缓存读取流程

```
  getBySkuId("SKU001")
        │
        ▼
  ┌─────────────┐     YES     ┌──────────────────┐
  │ Redis GET   ├────────────→│ Is it "NULL"?    │
  │ cache hit?  │             │ 是空值标记吗？     │
  │ 缓存命中?   │             ├─────┬────────────┤
  └──────┬──────┘             │ YES │     NO     │
         │ NO                 │     ▼            ▼
         │ 未命中              │  return      deserialize
         ▼                    │  empty       & return
  ┌─────────────┐             │  返回空       反序列化返回
  │ SETNX lock  │             └──────────────────┘
  │ 尝试获取锁   │
  ├──────┬──────┤
  │ GOT  │FAILED│
  │ 获得  │未获得 │
  │      │      │
  │      │      ▼
  │      │  sleep(50ms)
  │      │  retry read cache
  │      │  等待后重试读缓存
  │      │      │
  │      │      ▼
  │      │  (cache hit or fallback to DB)
  │      │  (命中缓存或兜底查DB)
  │      │
  ▼
  ┌─────────────────────────┐
  │ Double-check cache      │
  │ 双重检查缓存             │
  │ (another thread may     │
  │  have just written it)  │
  │ (其他线程可能刚写入)     │
  └──────────┬──────────────┘
             │ still miss / 仍未命中
             ▼
  ┌─────────────────────────┐
  │ Query MySQL             │
  └──────────┬──────────────┘
             │
     ┌───────┴────────┐
     │ Found          │ Not Found
     │ 找到            │ 未找到
     ▼                ▼
  ┌──────────┐  ┌──────────────┐
  │ SET cache│  │ SET "NULL"   │
  │ TTL=300  │  │ TTL=60s      │
  │ ±rand(60)│  │              │
  │ 写入缓存  │  │ 写入空值标记  │
  │ TTL随机   │  │ 短TTL        │
  └──────────┘  └──────────────┘
       │              │
       ▼              ▼
   DELETE lock    DELETE lock
   释放锁          释放锁
```

### TTL Jitter Example / TTL抖动示例

```
  Without jitter (无抖动):              With jitter (有抖动):
  All keys expire at T=300s             Keys expire at different times

  Time ─────────────────────→           Time ─────────────────────→
       |                   |                 |     |   | |  |   |
       0                  300s               240s 270s 290 310 340 360s
                           │                       │
                           ▼                       ▼
                    ALL requests hit DB      Requests spread out over time
                    所有请求同时打到DB        请求分散在不同时间

  Config / 配置:
    base TTL   = 300 seconds (5 minutes / 5分钟)
    jitter     = ±60 seconds (random / 随机)
    actual TTL = 240~360 seconds (randomly per key / 每个key随机)
```

### Key Source Files / 关键源文件

| File / 文件 | Role / 职责 |
|---|---|
| `service/ProductCacheService.java` | Full cache logic: mutex lock + null value + TTL jitter / 完整缓存逻辑 |

---

## 10. Feature 7: Kafka Retry & DLQ / 功能7：Kafka重试与死信队列

### Retry Strategy / 重试策略

```
  ┌──────────────────────────────────────────────────────────────────┐
  │                    Message Processing Flow                       │
  │                    消息处理流程                                    │
  │                                                                  │
  │                    ┌──────────────┐                               │
  │                    │ order-events │ (Main Topic / 主Topic)       │
  │                    └──────┬───────┘                               │
  │                           │                                      │
  │                           ▼                                      │
  │                    ┌──────────────┐                               │
  │                    │   Consumer   │                               │
  │                    │   消费者      │                               │
  │                    └──────┬───────┘                               │
  │                           │                                      │
  │              ┌────────────┼────────────┐                         │
  │              ▼            ▼            ▼                         │
  │         ┌────────┐  ┌─────────┐  ┌──────────┐                   │
  │         │SUCCESS │  │RETRYABLE│  │  NON-    │                   │
  │         │成功     │  │可重试    │  │RETRYABLE │                   │
  │         │        │  │  ERROR  │  │不可重试   │                   │
  │         │        │  │ (DB     │  │  ERROR   │                   │
  │         │        │  │ timeout)│  │(Insuff.  │                   │
  │         │        │  │(DB超时) │  │ stock)   │                   │
  │         │        │  │         │  │(库存不足) │                   │
  │         └───┬────┘  └────┬────┘  └────┬─────┘                   │
  │             │            │            │                          │
  │             ▼            ▼            ▼                          │
  │          ack()     retryCount     ack() +                       │
  │          确认       < MAX(3)?     mark fail                     │
  │                    重试次数<3?     确认+标记失败                   │
  │                    ┌───┴───┐                                     │
  │                   YES     NO                                     │
  │                    │       │                                     │
  │                    ▼       ▼                                     │
  │          ┌──────────────┐ ┌──────────────┐                      │
  │          │order-events- │ │order-events- │                      │
  │          │retry         │ │dlq           │                      │
  │          │(Retry Topic) │ │(Dead Letter) │                      │
  │          │重试Topic      │ │死信Topic      │                      │
  │          └──────┬───────┘ └──────────────┘                      │
  │                 │                   │                            │
  │                 │ consumed again    │ Manual intervention        │
  │                 │ 再次被消费         │ 人工介入处理                 │
  │                 ▼                   ▼                            │
  │          (back to Consumer)   (Alert / Dashboard)               │
  │          (回到消费者)          (告警 / 管理后台)                   │
  └──────────────────────────────────────────────────────────────────┘
```

### Classification of Errors / 异常分类

| Error Type / 异常类型 | Example / 示例 | Action / 处理 |
|---|---|---|
| **Retryable / 可重试** | DB connection timeout, network blip / DB连接超时、网络抖动 | Send to retry topic (max 3 times) / 发到重试topic（最多3次） |
| **Non-retryable / 不可重试** | Insufficient stock, invalid SKU / 库存不足、SKU无效 | Ack immediately, mark order as failed / 立即确认，标记订单失败 |
| **Exceeded retries / 超过重试** | Retried 3 times, still failing / 重试3次仍失败 | Send to DLQ for manual review / 发到死信队列人工处理 |

### Key Source Files / 关键源文件

| File / 文件 | Role / 职责 |
|---|---|
| `kafka/OrderKafkaConsumer.java` | Retry logic: catch → classify → retry or DLQ / 重试逻辑：捕获→分类→重试或死信 |
| `kafka/OrderKafkaProducer.java` | `sendToRetry()` and `sendToDlq()` methods |
| `kafka/InsufficientStockException.java` | Non-retryable exception marker / 不可重试异常标记 |
| `config/KafkaTopicConfig.java` | Creates retry + DLQ topics / 创建重试和死信topic |

---

## 11. Database Design / 数据库设计

### ER Diagram / ER关系图

```
  ┌────────────────────┐
  │    t_product        │
  │    商品表            │
  ├────────────────────┤         ┌───────────────────────┐
  │ PK id              │         │    t_order             │
  │ UK sku_id ─────────┼────┐    │    订单表              │
  │    name            │    │    ├───────────────────────┤
  │    price           │    │    │ PK id                 │
  │    stock           │    ├───→│    sku_id             │
  │    description     │    │    │ UK order_no           │
  │    image_url       │    │    │    quantity            │
  │    created_at      │    │    │    amount              │
  │    updated_at      │    │    │    status (0/1/2)      │
  └────────────────────┘    │    │    created_at          │
                            │    │    updated_at          │
  ┌────────────────────┐    │    └───────────────────────┘
  │    t_promotion      │    │
  │    促销表            │    │
  ├────────────────────┤    │    ┌───────────────────────┐
  │ PK id              │    │    │    t_outbox            │
  │ IX sku_id ─────────┼────┤    │    发件箱表             │
  │    label           │    │    ├───────────────────────┤
  │    discount        │    │    │ PK id                 │
  │    start_time      │    │    │    aggregate_type      │
  │    end_time        │    │    │    aggregate_id        │
  └────────────────────┘    │    │    event_type          │
                            │    │    payload (TEXT)       │
  ┌────────────────────┐    │    │    status (0/1)        │
  │ t_processed_event  │    │    │    created_at          │
  │ 已处理事件表        │    │    └───────────────────────┘
  ├────────────────────┤    │
  │ PK event_id ───────┼────┘ (logical, not FK)
  │    processed_at    │       (逻辑关联，非外键)
  └────────────────────┘
```

### Table Details / 表详情

#### t_product (商品表)

| Column | Type | Constraint | Description / 描述 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | Primary key / 主键 |
| sku_id | VARCHAR(64) | UNIQUE | SKU identifier / SKU编号 |
| name | VARCHAR(128) | NOT NULL | Product name / 商品名称 |
| price | DECIMAL(10,2) | NOT NULL | Price / 价格 |
| stock | INT | NOT NULL | Inventory count / 库存数量 |
| description | VARCHAR(512) | | Description / 描述 |
| image_url | VARCHAR(256) | | Image URL / 图片URL |
| created_at | DATETIME | | Creation time / 创建时间 |
| updated_at | DATETIME | | Update time / 更新时间 |

#### t_order (订单表)

| Column | Type | Constraint | Description / 描述 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | Primary key / 主键 |
| order_no | VARCHAR(64) | UNIQUE | Order number / 订单号 |
| sku_id | VARCHAR(64) | NOT NULL | Product SKU / 商品SKU |
| quantity | INT | NOT NULL | Order quantity / 下单数量 |
| amount | DECIMAL(10,2) | NOT NULL | Total amount / 总金额 |
| status | TINYINT | NOT NULL | 0=pending, 1=completed, 2=failed / 0=待处理 1=已完成 2=失败 |
| created_at | DATETIME | | Creation time / 创建时间 |
| updated_at | DATETIME | | Update time / 更新时间 |

#### t_outbox (发件箱表)

| Column | Type | Constraint | Description / 描述 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | Primary key / 主键 |
| aggregate_type | VARCHAR(64) | NOT NULL | Entity type, e.g., "ORDER" / 聚合类型 |
| aggregate_id | VARCHAR(64) | NOT NULL | Entity ID, e.g., orderNo / 聚合ID |
| event_type | VARCHAR(64) | NOT NULL | Event type, e.g., "ORDER_CREATED" / 事件类型 |
| payload | TEXT | NOT NULL | JSON event body / JSON事件内容 |
| status | TINYINT | NOT NULL | 0=pending, 1=published / 0=待发布 1=已发布 |
| created_at | DATETIME | | Creation time / 创建时间 |

#### t_processed_event (幂等表)

| Column | Type | Constraint | Description / 描述 |
|---|---|---|---|
| event_id | VARCHAR(64) | PK | Unique event ID (UUID) / 唯一事件ID |
| processed_at | DATETIME | | Processing time / 处理时间 |

#### t_promotion (促销表)

| Column | Type | Constraint | Description / 描述 |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | Primary key / 主键 |
| sku_id | VARCHAR(64) | INDEX | Product SKU / 商品SKU |
| label | VARCHAR(128) | NOT NULL | Promotion label / 促销标签 |
| discount | DECIMAL(3,2) | NOT NULL | Discount rate (0.80 = 20% off) / 折扣率 |
| start_time | DATETIME | NOT NULL | Start time / 开始时间 |
| end_time | DATETIME | NOT NULL | End time / 结束时间 |

---

## 12. API Specification / API接口规范

### Product APIs / 商品接口

| Method | Path | Description / 描述 | Response |
|---|---|---|---|
| GET | `/api/products` | List all products / 商品列表 | `Product[]` |
| GET | `/api/products/{skuId}` | Product detail (concurrent aggregation) / 商品详情（并发聚合） | `ProductDetailVO` |
| POST | `/api/products` | Create product / 新增商品 | `Product` |
| PUT | `/api/products/{skuId}` | Update product / 更新商品 | `Product` |
| DELETE | `/api/products/{skuId}` | Delete product / 删除商品 | `204 No Content` |

### Order APIs / 订单接口

| Method | Path | Description / 描述 | Response |
|---|---|---|---|
| POST | `/api/orders` | Place order (async via Kafka) / 下单（Kafka异步） | `{orderNo, message}` |
| GET | `/api/orders/{orderNo}` | Query order status / 查询订单状态 | `Order` |
| GET | `/api/orders` | List all orders / 订单列表 | `Order[]` |

### Request/Response Examples / 请求/响应示例

#### POST /api/orders (Place Order / 下单)

```json
// Request 请求
{
  "skuId": "SKU001",
  "quantity": 1
}

// Response 响应 (immediate / 立即返回)
{
  "orderNo": "ORD-A1B2C3D4E5F6",
  "message": "订单已提交，正在处理中"
}
```

#### GET /api/products/SKU001 (Product Detail / 商品详情)

```json
// Response 响应
{
  "product": {
    "id": 1,
    "skuId": "SKU001",
    "name": "iPhone 15 Pro",
    "price": 7999.00,
    "stock": 98,
    "description": "Apple iPhone 15 Pro 256GB"
  },
  "realtimeStock": 97,
  "promotions": [
    {
      "label": "限时直降500",
      "discount": 0.94
    }
  ],
  "degradeInfo": null
}

// Response with degradation / 带降级的响应
{
  "product": { ... },
  "realtimeStock": 98,
  "promotions": [],
  "degradeInfo": {
    "stock": "库存数据可能有延迟",
    "promotion": "促销信息暂不可用"
  }
}
```

---

## 13. End-to-End Data Flow / 端到端数据流

### Write Path: Place Order / 写路径：下单

```
  ┌──────┐  ①Click "Buy"  ┌──────────┐  ②POST       ┌───────────┐
  │ User │───────────────→│  React   │─────────────→│ Spring    │
  │ 用户 │  点击"下单"     │  Frontend│  /api/orders │ Boot API  │
  └──────┘                └──────────┘              └─────┬─────┘
                                                         │
                          ③ Generate orderNo + eventId   │
                            生成订单号+事件ID              │
                                                         │
                          ④ Send to Kafka                │
                            发送到Kafka                   ▼
                                                   ┌───────────┐
                          ⑤ Return {orderNo}       │   Kafka   │
                            返回订单号              │  Broker   │
                               ◄───────────────────│ partition │
                                                   │ (by skuId)│
                                                   └─────┬─────┘
                                                         │
                          ⑥ Consumer polls               │ (async 异步)
                            消费者拉取                     │
                                                         ▼
                                                   ┌───────────┐
                                                   │ Consumer  │
                                                   └─────┬─────┘
                                                         │
                          ⑦ Begin MySQL Transaction      │
                            开启MySQL事务                 │
                                                         ▼
                          ┌──────────────────────────────────────┐
                          │ ⑧ INSERT IGNORE t_processed_event   │
                          │    (idempotency check / 幂等检查)    │
                          │                                      │
                          │ ⑨ UPDATE t_product stock=stock-1    │
                          │    WHERE stock>=1                    │
                          │    (atomic deduction / 原子扣减)     │
                          │                                      │
                          │ ⑩ INSERT t_order (status=1)         │
                          │    (create order / 创建订单)         │
                          │                                      │
                          │ ⑪ INSERT t_outbox (status=0)        │
                          │    (outbox event / 发件箱事件)       │
                          │                                      │
                          │ ⑫ COMMIT                            │
                          └──────────────────────────────────────┘
                                                         │
                          ⑬ ack() to Kafka                │
                            向Kafka确认                   │
                                                         ▼
                          ┌──────────────────────────────────────┐
                          │ ⑭ @Scheduled OutboxPublisher        │
                          │    (every 5s / 每5秒)               │
                          │                                      │
                          │    SELECT FROM t_outbox WHERE st=0  │
                          │    → send to Kafka "outbox-events"  │
                          │    → UPDATE status=1                │
                          └──────────────────────────────────────┘
```

### Read Path: Product Detail / 读路径：商品详情

```
  ┌──────┐  ①Navigate    ┌──────────┐  ②GET             ┌───────────┐
  │ User │──────────────→│  React   │──────────────────→│ Spring    │
  │ 用户 │  进入详情页    │  Frontend│  /products/SKU001 │ Boot API  │
  └──────┘               └──────────┘                   └─────┬─────┘
                                                              │
                      ③ CompletableFuture.supplyAsync × 3     │
                        三个异步任务并发发出                      │
                                                              │
                ┌─────────────────────┬───────────────────────┘
                │                     │                     │
                ▼                     ▼                     ▼
  ┌──────────────────┐  ┌───────────────────┐  ┌──────────────────┐
  │ ④ Query Redis    │  │ ⑤ Query MySQL     │  │ ⑥ Query MySQL    │
  │   for product    │  │   for realtime    │  │   for promotions │
  │   info           │  │   stock           │  │                  │
  │                  │  │                   │  │                  │
  │   查Redis缓存    │  │   查DB实时库存     │  │   查DB促销信息    │
  │   (with mutex    │  │   (no cache,      │  │                  │
  │    lock if miss) │  │    always fresh)  │  │                  │
  │   (未命中则      │  │   (不走缓存,      │  │                  │
  │    互斥锁回源)    │  │    保证准确)      │  │                  │
  └────────┬─────────┘  └─────────┬─────────┘  └────────┬─────────┘
           │ 500ms timeout        │ 300ms timeout        │ 300ms timeout
           ▼                      ▼                      ▼
  ┌────────────────────────────────────────────────────────────────┐
  │ ⑦ Aggregate results, apply timeout degradation               │
  │   聚合结果，对超时的部分应用降级                                 │
  │                                                                │
  │   Return ProductDetailVO to frontend                           │
  │   返回 ProductDetailVO 给前端                                   │
  └────────────────────────────────────────────────────────────────┘
                              │
                              ▼
  ┌──────────────────────────────────────────────────────────────┐
  │ ⑧ React renders:                                            │
  │    - Product info, price, description                        │
  │    - Realtime stock count                                    │
  │    - Promotion tags (if any)                                 │
  │    - Degradation warning banner (if any timeout occurred)    │
  │                                                              │
  │    React 渲染:                                               │
  │    - 商品信息、价格、描述                                      │
  │    - 实时库存数量                                              │
  │    - 促销标签（如果有）                                        │
  │    - 降级警告横幅（如果有超时）                                 │
  └──────────────────────────────────────────────────────────────┘
```

---

## 14. Failure Scenarios & Handling / 故障场景与处理

| # | Scenario / 场景 | Impact / 影响 | Handling / 处理 |
|---|---|---|---|
| 1 | Kafka broker down / Kafka宕机 | Orders cannot be submitted / 无法提交订单 | Producer fails fast, return error to user / 生产者快速失败，返回错误 |
| 2 | Consumer crashes mid-processing / 消费者处理中崩溃 | Uncommitted message redelivered / 未提交的消息会重新投递 | Idempotency table prevents double processing / 幂等表防止重复处理 |
| 3 | MySQL down / MySQL宕机 | All writes fail / 所有写入失败 | Kafka retains messages until MySQL recovers / Kafka保留消息直到MySQL恢复 |
| 4 | Redis down / Redis宕机 | Cache miss, all reads hit DB / 缓存失效，所有读请求打到DB | Fallback: query MySQL directly / 降级：直接查MySQL |
| 5 | Slow DB query (>300ms) / DB查询慢(>300ms) | Detail page partial timeout / 详情页部分超时 | CompletableFuture timeout → graceful degradation / 超时降级 |
| 6 | Duplicate Kafka message / Kafka消息重复 | Same event processed twice / 同一事件处理两次 | INSERT IGNORE returns 0 → skip / 返回0行→跳过 |
| 7 | App crashes after tx commit, before outbox publish / 应用在事务提交后outbox发布前崩溃 | Event not yet published / 事件未发布 | Next OutboxPublisher cycle picks it up / 下次定时任务扫描发布 |
| 8 | Hot SKU single-partition bottleneck / 热点SKU单分区瓶颈 | One partition overloaded / 单个分区过载 | Future: sub-bucketing or dedicated topic / 未来：子分桶或独立topic |

---

## 15. 日本語版 (Japanese Translation)

---

# 技術PRD：商品在庫フラッシュセールシステム

---

## 1. システム概要

本システムは、学習用プロジェクトとして設計された**最小限のフラッシュセール在庫サービス**です。**商品一覧 → 商品詳細 → 注文**という単一のビジネスシナリオを実装しながら、高同時接続環境下でシステムの**パフォーマンス**、**信頼性**、**データ整合性**を向上させる6つのコアバックエンド技術をカバーしています。

**目標：**
- 各技術が特定の実際の問題をどのように解決するかをデモンストレーション
- ビジネスロジックを可能な限りシンプルに保ち、インフラストラクチャパターンの学習に集中
- Docker Composeによるローカル開発用の実行可能なコードベースを提供

---

## 2. アーキテクチャ概要

### システムアーキテクチャ図

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     クライアント (React + Vite)                          │
│                                                                         │
│   ┌──────────────┐   ┌──────────────────┐   ┌───────────────────┐      │
│   │ 商品一覧      │   │ 商品詳細          │   │ 注文一覧          │      │
│   └──────┬───────┘   └────────┬─────────┘   └────────┬──────────┘      │
└──────────┼────────────────────┼───────────────────────┼──────────────────┘
           │                    │                       │
           ▼                    ▼                       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    Spring Boot アプリケーション                           │
│                                                                         │
│  ┌──────────────┐  ┌─────────────────┐  ┌───────────────────┐          │
│  │ 商品サービス  │  │ 商品詳細サービス  │  │ 注文サービス       │          │
│  │ (CRUD)       │  │(CompleteFuture) │  │ (Kafka送信)       │          │
│  └──────┬───────┘  └───────┬─────────┘  └─────────┬─────────┘          │
│         │                  │                      │                     │
│  ┌──────▼───────┐          │           ┌──────────▼──────────┐          │
│  │ Redis Cache  │◄─────────┘           │  Kafka Producer     │          │
│  │ (排他ロック)  │                      │  (key=skuId)        │          │
│  └──────────────┘                      └──────────┬──────────┘          │
└──────────────────────────────────────────────────┼───────────────────────┘
                                                   │
           ┌───────────────┐              ┌────────▼──────────┐
           │    Redis      │              │     Kafka         │
           │  (ホットデータ  │              │  (12パーティション) │
           │   排他ロック    │              └────────┬──────────┘
           │   TTLジッター)  │                       │
           └───────────────┘              ┌────────▼──────────┐
                                          │   Consumer        │
                                          │   (非同期処理)      │
                                          └────────┬──────────┘
                                                   │
                              ┌─────────────────────────────────────────┐
                              │        MySQL — 単一トランザクション       │
                              │                                         │
                              │  ① INSERT IGNORE t_processed_event     │
                              │     (冪等性チェック)                      │
                              │  ② UPDATE t_product stock=stock-qty    │
                              │     WHERE stock>=qty (原子的在庫差引)    │
                              │  ③ INSERT t_order (注文作成)            │
                              │  ④ INSERT t_outbox (Outboxイベント)     │
                              └─────────────────────────────────────────┘
```

---

## 3. 技術スタック

| レイヤー | 技術 | 用途 |
|---|---|---|
| フロントエンド | React 18 + Vite + React Router | SPA、商品閲覧・注文UI |
| バックエンド | Spring Boot 3.2 + JPA + Lombok | REST API、ビジネスロジック |
| メッセージキュー | Apache Kafka 3.7 (KRaft) | 非同期書き込みバッファ、イベントストリーミング |
| データベース | MySQL 8.0 | 永続化ストレージ、トランザクション書き込み |
| キャッシュ | Redis 7 | ホットデータキャッシュ、分散ロック |
| インフラ | Docker Compose | ワンクリックローカル環境 |

---

## 4. 機能1：ピークシェービング — Kafka非同期書き込み

### 問題

フラッシュセールシナリオでは、数千の注文リクエストが同時にサーバーに到達します。各リクエストが直接MySQLに書き込むと、データベース接続プールが枯渇し、システムがクラッシュします。

### 解決策

```
  Kafkaなし:                                Kafkaあり:

  ┌──────┐  1000 req/s   ┌───────┐         ┌──────┐  1000 req/s  ┌───────┐
  │Client├───────────────→│ MySQL │ ← 障害!  │Client├─────────────→│ Kafka │
  └──────┘                └───────┘         └──────┘              └───┬───┘
                                                                      │
                                                           50 msg/s   │ 制御された
                                                                      │ 消費
                                                                      ▼
                                                                 ┌───────┐
                                                                 │ MySQL │ ← 安定!
                                                                 └───────┘
```

### ビジネスロジック

1. ユーザーが「今すぐ注文」をクリック → フロントエンドが `POST /api/orders` を送信（`{skuId, quantity}`）
2. `OrderService` がユニークな `orderNo` と `eventId` を生成
3. DBに直接書き込まず、**`OrderEvent` を Kafkaの `order-events` トピックに送信**
4. Kafkaメッセージのキーを `skuId` に設定 → 同じ商品の全注文が**同じパーティション**にルーティング（SKUごとの順序処理を実現）
5. APIは**即座に**注文番号と「注文送信済み、処理中」メッセージを返却
6. コンシューマ側の `OrderKafkaConsumer` が制御されたレートでメッセージをプルし処理

### なぜ12パーティション？

12は1、2、3、4、6、12で割り切れるため、コンシューマのスケーリングが容易です。4つのコンシューマインスタンスでは各3パーティション、6つにスケールすると各2パーティションになります。本番環境では12、24、48が一般的です。

---

## 5. 機能2：Outboxパターン — 結果整合性

### 問題

在庫差引・注文作成後、下流システム（通知サービス、分析など）に通知が必要です。DBコミット後にKafkaに送信し、コミットと送信の間にアプリがクラッシュすると、イベントが失われます。コミット前に送信し、トランザクションがロールバックすると、ファントムイベントを送信したことになります。

### 解決策：トランザクショナルOutbox

```
  ┌──────────────── MySQLトランザクション ───────────────┐
  │                                                      │
  │   ① UPDATE t_product (在庫差引)                      │
  │   ② INSERT t_order   (注文作成)                      │
  │   ③ INSERT t_outbox  (イベント保存)                   │
  │                                                      │
  │   → 3つ全て成功するか、全てロールバック                  │
  └──────────────────────────────────────────────────────┘

  ┌──────── バックグラウンドジョブ (@Scheduled 5秒) ──────┐
  │                                                      │
  │   SELECT * FROM t_outbox WHERE status = 0            │
  │   → kafkaTemplate.send(topic, payload)               │
  │   → UPDATE t_outbox SET status = 1                   │
  └──────────────────────────────────────────────────────┘
```

### ビジネスロジック

1. `OrderProcessingService.processOrder()` 内 — 単一の `@Transactional` メソッド：
   - 在庫を差引（原子的SQL）
   - 注文レコードを作成
   - **Outbox行を挿入**（`status=0`（未発行）、シリアライズされた注文イベントJSONをペイロードとして含む）
2. 3つの書き込みが1つのトランザクション内 — 原子的に一緒に成功または失敗
3. `OutboxPublisher` が `@Scheduled` で5秒ごとに実行：
   - `t_outbox WHERE status=0 LIMIT 100` をクエリ
   - 各未発行イベントをKafkaに送信
   - 送信成功後、`status=1`（発行済み）に更新
   - 失敗時はループを中断 — 次のサイクルでリトライ
4. アプリがトランザクションコミット後、Outbox発行前にクラッシュしても、イベントはMySQLに永続化されており、次回起動時に発行される

---

## 6. 機能3：冪等性 — メッセージ重複排除

### 問題

Kafkaは**少なくとも1回の配信**を保証 — 同じメッセージが複数回配信される可能性があります（コンシューマがack前にクラッシュ、ネットワーク分断、Outbox再発行など）。重複排除なしでは、同じ注文が在庫を二重に差引する可能性があります。

### 解決策：INSERT IGNORE

```
  メッセージ初回到着                        メッセージ重複到着

  INSERT IGNORE INTO                     INSERT IGNORE INTO
  t_processed_event                      t_processed_event
  (event_id) VALUES (?)                  (event_id) VALUES (?)

  結果: 1行影響                           結果: 0行影響

  → 処理を続行                            → スキップ（即座にリターン）
```

### ビジネスロジック

1. 各 `OrderEvent` はユニークな `eventId`（UUID）を持つ
2. `OrderProcessingService.processOrder()` の冒頭、ビジネスロジックの前に：
   - `INSERT IGNORE INTO t_processed_event (event_id) VALUES (?)` を実行
   - `INSERT IGNORE` はMySQL固有の構文：主キーが既に存在する場合、何もせず影響行数0を返す
3. `影響行数 == 0` → このイベントは処理済み → **即座にスキップ**
4. `影響行数 == 1` → 初回処理 → 在庫差引を続行
5. このINSERTはビジネス書き込みと同じトランザクション内のため原子的

---

## 7. 機能4：原子的在庫差引

### 問題

同時接続下で、先読み後書きの在庫差引は過剰販売を引き起こします。スレッドAがstock=1を読み、スレッドBもstock=1を読み、両方が在庫十分と判断し、両方がstock=0に書き込む — しかし商品は1つしかありませんでした。

### 解決策：原子的SQL + Kafkaパーティション順序

```
  UPDATE t_product
  SET stock = stock - #{qty}
  WHERE sku_id = #{skuId}
    AND stock >= #{qty}     ← 過剰販売防止のガード条件

  影響行数 = 1  →  成功（差引完了）
  影響行数 = 0  →  在庫不足
```

- Kafkaメッセージの `key=skuId` により、同じSKUの全注文が同じパーティションにルーティング
- パーティション内のメッセージは順序通り消費される
- 原子的SQLだけでも過剰販売を防止できるが、Kafkaパーティション順序により同一行のロック競合も排除

---

## 8. 機能5：並行読み取り集約

### 問題

商品詳細ページは3つのデータソースからデータが必要です。順次クエリ（各約100ms）の場合、合計レイテンシ = 300ms。

### 解決策：CompletableFuture + タイムアウト劣化

```
  順次クエリ: 合計 ~300ms              並行クエリ: 合計 ~100ms

  [Query 1 100ms]→[Query 2 100ms]→   [Query 1 100ms]──┐
  [Query 3 100ms]                     [Query 2 100ms]──┼──→ 集約
                                      [Query 3 100ms]──┘
```

### ビジネスロジック

1. `ProductDetailService.getProductDetail(skuId)` が3つの非同期クエリを同時に発火：
   - **クエリ1：** 商品基本情報 — **Redisキャッシュ**から読み取り（機能6）
   - **クエリ2：** リアルタイム在庫 — **MySQLから直接読み取り**（キャッシュなし、正確性を保証）
   - **クエリ3：** アクティブなプロモーション — MySQLから読み取り
2. 各クエリはスレッドプール（`queryExecutor`、8スレッド）で実行
3. 各結果は**タイムアウト付き**で待機：
   - 商品情報：500msタイムアウト
   - リアルタイム在庫：300msタイムアウト
   - プロモーション：300msタイムアウト
4. クエリが**タイムアウトまたは失敗**した場合、**グレースフルデグラデーション**：
   - 商品情報タイムアウト → `「基本情報は一時的に利用不可」`
   - 在庫タイムアウト → キャッシュされた在庫値にフォールバック
   - プロモーションタイムアウト → 空のプロモーションリストを返却

---

## 9. 機能6：Redisキャッシュ戦略

### 解決する3つのキャッシュ問題

| 問題 | 説明 | 解決策 |
|---|---|---|
| **キャッシュ穿透** | 存在しないデータへのクエリが毎回DBに到達 | 空値をキャッシュ（60秒TTL） |
| **キャッシュ穿破** | ホットキーが期限切れ → 多数のスレッドが同時にDBにアクセス | 排他ロック（SETNX）：1スレッドのみDBからロード |
| **キャッシュ雪崩** | 多数のキーが同時に期限切れ → 全リクエストが同時にDBにアクセス | TTLジッター：TTL = 300 ± random(60)秒 |

### キャッシュ読み取りフロー

1. Redis GET → ヒット → デシリアライズして返却
2. ミス → SETNXでロック取得を試行
3. ロック取得成功 → 二重チェック → MySQL照会 → キャッシュに書き込み（TTLジッター付き） → ロック解放
4. ロック取得失敗 → 50ms待機 → キャッシュ再読み取り → まだミスならDB直接照会

### TTLジッター設定

```
  ベースTTL    = 300秒（5分）
  ジッター     = ±60秒（ランダム）
  実際のTTL    = 240〜360秒（キーごとにランダム）
```

---

## 10. 機能7：Kafkaリトライ＆デッドレターキュー

### リトライ戦略

| エラータイプ | 例 | 処理 |
|---|---|---|
| **リトライ可能** | DB接続タイムアウト、ネットワーク障害 | リトライtopicに送信（最大3回） |
| **リトライ不可** | 在庫不足、無効なSKU | 即座にack、注文を失敗としてマーク |
| **リトライ超過** | 3回リトライしても失敗 | DLQ（デッドレターキュー）に送信、手動対応 |

### フロー

```
  order-events (メインtopic)
        │
        ▼
    Consumer (コンシューマ)
        │
   ┌────┼────┐
   ▼    ▼    ▼
  成功  リトライ可能  リトライ不可
   │    │           │
   ▼    ▼           ▼
  ack  count<3?    ack + 失敗マーク
      ┌──┴──┐
     YES   NO
      │     │
      ▼     ▼
   retry   DLQ
   topic   topic
```

---

## 11. データベース設計

### テーブル一覧

| テーブル名 | 説明 | 技術ポイント |
|---|---|---|
| `t_product` | 商品テーブル（SKU、名前、価格、在庫） | 原子的在庫差引SQL |
| `t_order` | 注文テーブル（注文番号、金額、ステータス） | Kafkaコンシューマ内で作成 |
| `t_outbox` | Outbox発信箱テーブル（イベントタイプ、ペイロード、ステータス） | トランザクショナルOutbox |
| `t_processed_event` | 処理済みイベントテーブル（event_id主キー） | INSERT IGNOREによる冪等性 |
| `t_promotion` | プロモーションテーブル（割引、期間） | CompletableFuture並行読み取り |

---

## 12. API仕様

### 商品API

| メソッド | パス | 説明 |
|---|---|---|
| GET | `/api/products` | 商品一覧 |
| GET | `/api/products/{skuId}` | 商品詳細（並行集約） |
| POST | `/api/products` | 商品作成 |
| PUT | `/api/products/{skuId}` | 商品更新 |
| DELETE | `/api/products/{skuId}` | 商品削除 |

### 注文API

| メソッド | パス | 説明 |
|---|---|---|
| POST | `/api/orders` | 注文（Kafka経由で非同期） |
| GET | `/api/orders/{orderNo}` | 注文ステータス照会 |
| GET | `/api/orders` | 注文一覧 |

---

## 13. エンドツーエンドデータフロー

### 書き込みパス：注文

1. ユーザーが「今すぐ注文」をクリック
2. React → `POST /api/orders {skuId, quantity}`
3. Spring Boot が orderNo + eventId を生成
4. Kafkaに送信（key=skuId）
5. 即座に `{orderNo, "処理中"}` を返却
6. Kafkaコンシューマがプル（非同期）
7. MySQLトランザクション開始
8. INSERT IGNORE t_processed_event（冪等性）
9. UPDATE t_product stock=stock-1 WHERE stock>=1（原子的差引）
10. INSERT t_order（注文作成）
11. INSERT t_outbox（Outboxイベント）
12. COMMIT
13. Kafkaにack
14. @Scheduled OutboxPublisher が未発行イベントを発行

### 読み取りパス：商品詳細

1. ユーザーが詳細ページに遷移
2. React → `GET /api/products/SKU001`
3. CompletableFuture × 3 並行発火
4. クエリ1：Redis → 商品情報（排他ロック付きキャッシュ）
5. クエリ2：MySQL → リアルタイム在庫
6. クエリ3：MySQL → プロモーション
7. 結果集約、タイムアウト劣化適用
8. React が商品情報・在庫・プロモーション・劣化警告を表示

---

## 14. 障害シナリオと対処

| # | シナリオ | 影響 | 対処 |
|---|---|---|---|
| 1 | Kafkaブローカー停止 | 注文送信不可 | プロデューサー即座に失敗、ユーザーにエラー返却 |
| 2 | コンシューマ処理中クラッシュ | 未コミットメッセージが再配信 | 冪等テーブルで二重処理防止 |
| 3 | MySQL停止 | 全書き込み失敗 | KafkaがMySQL復旧までメッセージを保持 |
| 4 | Redis停止 | キャッシュミス、全読み取りがDBに到達 | フォールバック：MySQLに直接クエリ |
| 5 | DBクエリ遅延（>300ms） | 詳細ページ部分タイムアウト | CompletableFutureタイムアウト → グレースフルデグラデーション |
| 6 | Kafkaメッセージ重複 | 同一イベント二重処理 | INSERT IGNOREが0を返却 → スキップ |
| 7 | txコミット後、Outbox発行前にクラッシュ | イベント未発行 | 次のOutboxPublisherサイクルで発行 |
| 8 | ホットSKU単一パーティションボトルネック | 1パーティションが過負荷 | 将来対応：サブバケッティングまたは専用topic |

---

*Document Version: 1.0 | Generated: 2026-03-23*
