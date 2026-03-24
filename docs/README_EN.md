<p align="center">
  <a href="../README.md"><img src="https://img.shields.io/badge/lang-中文-red?style=for-the-badge" alt="中文"></a>
  <a href="./README_EN.md"><img src="https://img.shields.io/badge/lang-English-blue?style=for-the-badge" alt="English"></a>
  <a href="./README_JA.md"><img src="https://img.shields.io/badge/lang-日本語-green?style=for-the-badge" alt="日本語"></a>
</p>

<p align="center">
  <b>📖 Detailed Engineering Guide → <a href="./DETAILED_GUIDE.md">docs/DETAILED_GUIDE.md</a></b><br>
  <b>📋 Technical PRD (Bilingual) → <a href="./TECHNICAL_PRD.md">docs/TECHNICAL_PRD.md</a></b>
</p>

---

# Inventory Flash-Sale System — Backend Learning Project

A minimal **Spring Boot 3 + React** project that uses a single business scenario — **browse products → view details → place order → deduct inventory** — to demonstrate 6 core backend techniques for building high-concurrency, large-scale systems.

## Architecture Overview

```
┌──────────┐     POST /api/orders      ┌──────────────┐
│  React   │ ──────────────────────────→│  Spring Boot │
│ Frontend │                            │  Controller  │
└──────────┘                            └──────┬───────┘
                                               │
                              ┌────────────────↓────────────────┐
                              │         Kafka Producer           │
                              │  key=skuId → same SKU same      │
                              │  partition (ordered processing)  │
                              └────────────────┬────────────────┘
                                               │
                              ┌────────────────↓────────────────┐
                              │         Kafka Consumer           │
                              │  (async consumption, peak        │
                              │   shaving & valley filling)      │
                              └────────────────┬────────────────┘
                                               │
                    ┌──────────────────────────────────────────────┐
                    │       Single MySQL Transaction                │
                    │                                              │
                    │  1. INSERT IGNORE t_processed_event (idemp.) │
                    │  2. UPDATE t_product SET stock=stock-1       │
                    │     WHERE stock>=1 (atomic deduction)        │
                    │  3. INSERT t_order (create order)            │
                    │  4. INSERT t_outbox (outbox event)           │
                    └──────────────────────────────────────────────┘
                                               │
                              ┌────────────────↓────────────────┐
                              │  Outbox Background Publisher     │
                              │  (@Scheduled) scan status=0     │
                              │  → send to Kafka → mark as 1    │
                              └─────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────┐
  │  GET /api/products/{skuId}  Product Detail (Read Path)      │
  │                                                             │
  │  CompletableFuture.supplyAsync fans out 3 queries:          │
  │    ① Product info (Redis cache → mutex lock → TTL jitter)   │
  │    ② Realtime stock (direct DB)                             │
  │    ③ Promotions (DB)                                        │
  │  Each with independent timeout; degrade on timeout          │
  └─────────────────────────────────────────────────────────────┘
```

## The 6 Core Techniques

| # | Technique | Key Files | What It Does |
|---|-----------|-----------|-------------|
| 1 | **Peak Shaving** (Kafka async) | `OrderKafkaProducer.java` / `OrderKafkaConsumer.java` | Order → Kafka → async consume, no sync DB blocking |
| 2 | **Outbox Eventual Consistency** | `OrderProcessingService.java` / `OutboxPublisher.java` | Write outbox in same TX, scheduled background publish |
| 3 | **Idempotency** (INSERT IGNORE) | `ProcessedEventRepository.java` / `OrderProcessingService.java` | `INSERT IGNORE t_processed_event` deduplicates messages |
| 4 | **Atomic Stock + Partition Order** | `ProductRepository.java` / `OrderKafkaProducer.java` | `stock=stock-1 WHERE stock>=1` + key=skuId partitioning |
| 5 | **CompletableFuture Parallel Read** | `ProductDetailService.java` | 3 async queries + per-query timeout degradation |
| 6 | **Redis Cache** (anti-stampede/avalanche) | `ProductCacheService.java` | Mutex lock + TTL jitter + null-value caching |

**Bonus:** Kafka retry topic + DLQ dead-letter queue → see `OrderKafkaConsumer.java`

## Quick Start

### 1. Start Infrastructure (MySQL + Redis + Kafka)

```bash
docker-compose up -d
```

### 2. Start Backend

```bash
cd inventory-service
mvn spring-boot:run
```

### 3. Start Frontend

```bash
cd frontend
npm install
npm run dev
```

Visit http://localhost:5173

## Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Framework | Spring Boot | 3.2.5 |
| Language | Java | 17 |
| ORM | Spring Data JPA + Hibernate 6 | — |
| Database | MySQL | 8.0 |
| Message Queue | Apache Kafka (KRaft) | 3.7.0 |
| Cache | Redis | 7 |
| Frontend | React + Vite | 18.3 / 5.2 |
| Container | Docker Compose | 3.8 |

## Project Structure

```
BackendExample/
├── docker-compose.yml          # One-command infra: MySQL/Redis/Kafka
├── sql/schema.sql              # DB schema + seed data
├── inventory-service/          # Spring Boot backend
│   └── src/main/java/com/example/inventory/
│       ├── config/             # Kafka topics, Redis, async thread pool, CORS
│       ├── entity/             # JPA entities: Product, Order, Outbox, ProcessedEvent, Promotion
│       ├── repository/         # Data access (atomic stock SQL, INSERT IGNORE)
│       ├── kafka/              # Producer / Consumer / Event model
│       ├── outbox/             # Outbox background scheduled publisher
│       ├── service/            # Business logic
│       │   ├── OrderService.java           # Place order → send to Kafka
│       │   ├── OrderProcessingService.java # Consumer core (idempotent + deduct + outbox)
│       │   ├── ProductCacheService.java    # Redis cache (mutex + TTL jitter)
│       │   ├── ProductDetailService.java   # CompletableFuture aggregation
│       │   └── ProductService.java         # Product CRUD
│       └── controller/         # REST API endpoints
└── frontend/                   # React + Vite frontend
    └── src/
        ├── api/index.js        # API call wrappers
        └── components/         # ProductList, ProductDetail, OrderList
```

## Interview Extension Topics

### Kafka Retry & DLQ
- **Retryable exceptions** (DB timeout, etc.) → send to `order-events-retry` topic, max 3 retries
- **Non-retryable exceptions** (insufficient stock) → mark as failed, no retry
- **Exceeded max retries** → send to `order-events-dlq` dead-letter queue for manual handling

### Topic Partition Count
- This project uses **12 partitions** — suitable for medium scale (consumers ≤ partitions)
- Production commonly uses **12 / 24 / 48** depending on consumer throughput
- **Hot SKU bottleneck**: all messages for one SKU land in the same partition; solve via "sub-bucketing within partition" or "dedicated topic for hot SKUs"
