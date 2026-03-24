# Inventory Flash-Sale System — Detailed Engineering Guide

> A minimal Spring Boot 3 + React project that uses a single business scenario — **"place order & deduct inventory"** — to demonstrate 6 core backend techniques for building high-concurrency, large-scale systems.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Technology Stack](#2-technology-stack)
3. [Project Structure — File-by-File Breakdown](#3-project-structure--file-by-file-breakdown)
   - [Infrastructure & Configuration](#31-infrastructure--configuration)
   - [Database Schema](#32-database-schema)
   - [Entity Layer](#33-entity-layer)
   - [Repository Layer](#34-repository-layer)
   - [Kafka Layer (Producer / Consumer / Event)](#35-kafka-layer)
   - [Outbox Publisher](#36-outbox-publisher)
   - [Service Layer](#37-service-layer)
   - [Controller Layer (REST API)](#38-controller-layer)
   - [Frontend (React + Vite)](#39-frontend-react--vite)
4. [The 6 Core Techniques — Deep Dive](#4-the-6-core-techniques--deep-dive)
   - [Technique 1: Peak Shaving with Kafka](#technique-1-peak-shaving-with-kafka)
   - [Technique 2: Transactional Outbox for Eventual Consistency](#technique-2-transactional-outbox-for-eventual-consistency)
   - [Technique 3: Idempotent Processing with INSERT IGNORE](#technique-3-idempotent-processing-with-insert-ignore)
   - [Technique 4: Atomic Inventory Deduction + Partition Ordering](#technique-4-atomic-inventory-deduction--partition-ordering)
   - [Technique 5: CompletableFuture Concurrent Aggregation + Timeout Degradation](#technique-5-completablefuture-concurrent-aggregation--timeout-degradation)
   - [Technique 6: Redis Cache — Anti-Stampede / Anti-Avalanche](#technique-6-redis-cache--anti-stampede--anti-avalanche)
5. [Advanced Topics: Kafka Retry & DLQ](#5-advanced-topics-kafka-retry--dlq)
6. [Data Flow Diagrams](#6-data-flow-diagrams)
7. [API Reference](#7-api-reference)
8. [How to Run](#8-how-to-run)

---

## 1. Architecture Overview

```
┌────────────────┐                               ┌────────────────────────┐
│   React SPA    │   POST /api/orders             │  Spring Boot 3 (8080)  │
│  (Vite :5173)  │──────────────────────────────→ │   OrderController      │
└────────────────┘                                └───────────┬────────────┘
                                                              │
                                                              ▼
                                              ┌───────────────────────────────┐
                                              │  OrderService.placeOrder()    │
                                              │  Generate orderNo + eventId   │
                                              │  Send to Kafka (key=skuId)    │
                                              │  Return orderNo immediately   │
                                              └──────────────┬────────────────┘
                                                             │
                    ┌────────────────────────────────────────────────────────────┐
                    │              Apache Kafka (KRaft, 12 partitions)           │
                    │                                                            │
                    │  Topic: order-events        key=skuId                      │
                    │  Topic: order-events-retry   (failed retries)              │
                    │  Topic: order-events-dlq     (dead letter queue)           │
                    └────────────────────────────────────┬───────────────────────┘
                                                         │
                                                         ▼
                                          ┌──────────────────────────────┐
                                          │  OrderKafkaConsumer          │
                                          │  (manual ack, at-least-once) │
                                          └──────────────┬───────────────┘
                                                         │
                                                         ▼
                              ┌──────────────────────────────────────────────────────┐
                              │       OrderProcessingService.processOrder()          │
                              │       ALL 4 STEPS IN A SINGLE MySQL TRANSACTION      │
                              │                                                      │
                              │  Step 1: INSERT IGNORE t_processed_event (idempotent)│
                              │  Step 2: UPDATE t_product stock=stock-qty             │
                              │          WHERE stock >= qty (atomic deduction)        │
                              │  Step 3: INSERT t_order (create order record)         │
                              │  Step 4: INSERT t_outbox (transactional outbox)       │
                              └──────────────────────────────────────────────────────┘
                                                         │
                              ┌──────────────────────────────────────────────────────┐
                              │       OutboxPublisher (@Scheduled every 5s)          │
                              │  Scan t_outbox WHERE status=0 → send to Kafka        │
                              │  → mark status=1 → eventual consistency              │
                              └──────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────────────┐
  │  READ PATH: GET /api/products/{skuId}                                           │
  │                                                                                  │
  │  ProductDetailService uses CompletableFuture to fan out 3 queries in parallel:  │
  │    ┌─────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐  │
  │    │ ① Product info      │  │ ② Realtime stock     │  │ ③ Promotions         │  │
  │    │    (Redis cache)    │  │    (direct DB)        │  │    (direct DB)       │  │
  │    │    500ms timeout    │  │    300ms timeout      │  │    300ms timeout     │  │
  │    └─────────────────────┘  └──────────────────────┘  └──────────────────────┘  │
  │                                                                                  │
  │  If any query times out → graceful degradation with default values               │
  └──────────────────────────────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────────────┐
  │  CACHE LAYER: ProductCacheService                                                │
  │                                                                                  │
  │  Redis GET → HIT? return                                                         │
  │            → MISS? acquire mutex lock (SET NX EX 10s)                            │
  │                    → GOT LOCK: double-check cache → query DB → SET with jittered │
  │                                TTL (300s ± 60s) → release lock                   │
  │                    → NO LOCK:  sleep 50ms → retry cache → fallback to DB         │
  │                                                                                  │
  │  Null value cached as "NULL" for 60s to prevent cache penetration                │
  └──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Technology Stack

| Layer | Technology | Version | Purpose |
|---|---|---|---|
| **Framework** | Spring Boot | 3.2.5 | Application framework (Jakarta EE namespace) |
| **Language** | Java | 17 | LTS version required by Spring Boot 3 |
| **ORM** | Spring Data JPA + Hibernate | — | Database access, entity mapping |
| **Database** | MySQL | 8.0 | Primary data store (InnoDB, utf8mb4) |
| **Message Queue** | Apache Kafka | 3.7.0 | Async order processing, peak shaving |
| **Cache** | Redis | 7 (Alpine) | Hot product caching, distributed mutex |
| **Build** | Maven | 3.9.x | Dependency management |
| **Frontend** | React + Vite | 18.3 / 5.2 | Single-page application |
| **Routing** | React Router | 6.23 | Client-side routing |
| **Container** | Docker Compose | 3.8 | One-command infra setup |
| **Code Generation** | Lombok | — | Reduce boilerplate (@Data, @Slf4j, etc.) |

---

## 3. Project Structure — File-by-File Breakdown

```
BackendExample/
├── docker-compose.yml                    # [Infra] Docker services definition
├── sql/
│   └── schema.sql                        # [DB] Table definitions + seed data
├── inventory-service/                    # [Backend] Spring Boot application
│   ├── pom.xml                           # [Build] Maven dependencies
│   ├── start-backend.cjs                 # [Helper] Node.js script to launch backend
│   └── src/main/
│       ├── resources/
│       │   └── application.yml           # [Config] All Spring Boot configuration
│       └── java/com/example/inventory/
│           ├── InventoryApplication.java # [Entry] Main class, @EnableScheduling
│           ├── config/
│           │   ├── AsyncConfig.java      # [Config] Thread pool for CompletableFuture
│           │   ├── KafkaTopicConfig.java # [Config] Topic creation (main/retry/DLQ)
│           │   ├── RedisConfig.java      # [Config] StringRedisTemplate bean
│           │   └── WebConfig.java        # [Config] CORS for frontend
│           ├── entity/
│           │   ├── Product.java          # [Entity] Product/SKU with stock
│           │   ├── Order.java            # [Entity] Order record
│           │   ├── Outbox.java           # [Entity] Transactional outbox record
│           │   ├── ProcessedEvent.java   # [Entity] Idempotency dedup record
│           │   └── Promotion.java        # [Entity] Promotion/discount info
│           ├── repository/
│           │   ├── ProductRepository.java       # [Repo] Atomic stock deduction SQL
│           │   ├── OrderRepository.java         # [Repo] Order CRUD
│           │   ├── OutboxRepository.java        # [Repo] Find pending outbox events
│           │   ├── ProcessedEventRepository.java # [Repo] INSERT IGNORE idempotency
│           │   └── PromotionRepository.java     # [Repo] Active promotions query
│           ├── kafka/
│           │   ├── OrderEvent.java              # [DTO] Kafka message payload
│           │   ├── OrderKafkaProducer.java       # [Kafka] Send to main/retry/DLQ topics
│           │   ├── OrderKafkaConsumer.java        # [Kafka] Consume + retry logic
│           │   └── InsufficientStockException.java # [Exception] Non-retryable
│           ├── outbox/
│           │   └── OutboxPublisher.java          # [Scheduler] Poll & publish outbox events
│           └── service/
│               ├── OrderService.java             # [Service] Place order → Kafka
│               ├── OrderProcessingService.java   # [Service] Core: idempotent + deduct + outbox
│               ├── ProductService.java           # [Service] Product CRUD
│               ├── ProductCacheService.java      # [Service] Redis cache with mutex + jitter
│               └── ProductDetailService.java     # [Service] CompletableFuture aggregation
├── frontend/                              # [Frontend] React SPA
│   ├── index.html                        # [HTML] Entry HTML
│   ├── package.json                      # [NPM] Dependencies (React 18, Vite 5)
│   ├── vite.config.js                    # [Config] Dev server + API proxy to :8080
│   ├── start.cjs                         # [Helper] Node.js script to launch frontend
│   └── src/
│       ├── main.jsx                      # [Entry] App component, React Router setup
│       ├── index.css                     # [Style] Global CSS (cards, tables, toast)
│       ├── api/
│       │   └── index.js                  # [API] fetch wrappers for all REST endpoints
│       └── components/
│           ├── ProductList.jsx           # [Page] Product grid, click → detail
│           ├── ProductDetail.jsx         # [Page] Detail + order + degradation display
│           └── OrderList.jsx             # [Page] Order table with status
└── docs/
    └── TECHNICAL_PRD.md                  # [Doc] Bilingual technical PRD
```

---

### 3.1 Infrastructure & Configuration

#### `docker-compose.yml`

Defines 3 Docker services to run all infrastructure with a single `docker-compose up -d`:

| Service | Image | Port | Notes |
|---|---|---|---|
| **mysql** | `mysql:8.0` | 3306 | Root password: `root123`, DB: `inventory_db`. Auto-initializes from `sql/schema.sql` via Docker entrypoint. Configured with `utf8mb4` charset and `mysql_native_password` auth plugin. Data persisted in `mysql-data` volume. |
| **redis** | `redis:7-alpine` | 6379 | Lightweight Alpine image. No password (dev mode). Used for product caching and distributed mutex locks. |
| **kafka** | `apache/kafka:3.7.0` | 9092 | **KRaft mode** (no Zookeeper needed). Single broker with both broker + controller roles. Cluster ID is hardcoded for deterministic startup. Replication factor = 1 (single-node dev). |

**Why KRaft mode?** Kafka 3.3+ supports KRaft as production-ready, eliminating the need for a separate Zookeeper cluster. This simplifies deployment from 2 containers (ZK + Kafka) to 1.

---

#### `inventory-service/pom.xml`

Maven build file for the Spring Boot application:

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | REST API, embedded Tomcat |
| `spring-boot-starter-data-jpa` | Hibernate ORM, JpaRepository |
| `mysql-connector-j` | MySQL JDBC driver |
| `spring-boot-starter-data-redis` | Redis client (Lettuce) |
| `spring-kafka` | Kafka producer/consumer integration |
| `jackson-databind` | JSON serialization for Kafka messages |
| `lombok` | Compile-time code generation (@Data, @Slf4j, etc.) |

**Key**: Uses `spring-boot-starter-parent:3.2.5` (Spring Boot 3.x) which mandates:
- Java 17+
- `jakarta.*` namespace (not `javax.*`)
- Hibernate 6.x

---

#### `application.yml`

Central configuration file with 4 sections:

```yaml
server.port: 8080                          # Backend HTTP port

spring.datasource:                          # MySQL connection
  url: jdbc:mysql://localhost:3306/inventory_db?characterEncoding=UTF-8
  username: root / password: root123

spring.jpa:
  hibernate.ddl-auto: validate             # Don't auto-create tables; validate against schema.sql
  show-sql: true                           # Log SQL for learning

spring.kafka:
  bootstrap-servers: localhost:9092
  consumer.enable-auto-commit: false       # CRITICAL: manual ack for at-least-once delivery
  listener.ack-mode: manual                # Consumer controls when offset is committed
  producer.acks: all                       # Wait for all in-sync replicas (durability)

app.kafka:                                 # Custom topic names
  order-topic: order-events
  order-topic-partitions: 12               # 12 partitions for parallelism
  retry-topic: order-events-retry
  dlq-topic: order-events-dlq

app.cache:
  product-ttl-seconds: 300                 # 5-minute base TTL for product cache
  product-ttl-jitter-seconds: 60           # ±60s random jitter to prevent avalanche
```

**Why `enable-auto-commit: false`?** Auto-commit commits offsets on a timer, meaning a crash between commit and processing causes message loss. Manual ack ensures the offset is only committed after successful processing.

---

#### `InventoryApplication.java`

```java
@SpringBootApplication
@EnableScheduling  // Enables @Scheduled for OutboxPublisher
public class InventoryApplication { ... }
```

The application entry point. `@EnableScheduling` is required for the Outbox pattern — without it, the `@Scheduled` method in `OutboxPublisher` would never execute.

---

#### `AsyncConfig.java`

```java
@Bean
public ExecutorService queryExecutor() {
    return Executors.newFixedThreadPool(8);
}
```

Creates a dedicated thread pool with 8 threads for `CompletableFuture` parallel queries in `ProductDetailService`. Without this, `CompletableFuture.supplyAsync()` uses `ForkJoinPool.commonPool()` which is shared globally and can be starved by other tasks.

**Why 8 threads?** For I/O-bound work (DB queries, Redis calls), the pool should be larger than CPU cores. 8 is a reasonable default for 3 concurrent queries per request.

---

#### `KafkaTopicConfig.java`

Programmatically creates 3 Kafka topics on application startup:

| Topic | Partitions | Purpose |
|---|---|---|
| `order-events` | 12 | Main order processing topic |
| `order-events-retry` | 12 | Failed messages with retry count < 3 |
| `order-events-dlq` | 12 | Dead letter queue for messages that exceeded retries |

**Why 12 partitions?** It sets the upper bound on consumer parallelism (max 12 consumer instances in the same group). Common production values: 12/24/48. Must be ≥ the number of consumer instances.

---

#### `RedisConfig.java`

Simple bean definition for `StringRedisTemplate`. Uses Lettuce (default Redis client in Spring Boot) with string serialization for both keys and values. JSON objects are serialized/deserialized manually with Jackson in `ProductCacheService`.

---

#### `WebConfig.java`

CORS configuration allowing the React dev server (`localhost:5173`) to call the backend (`localhost:8080`). Permits GET, POST, PUT, DELETE with credentials.

---

### 3.2 Database Schema

#### `sql/schema.sql`

Creates 5 tables in `inventory_db`. Auto-executed by MySQL Docker on first startup.

##### `t_product` — Product Table

| Column | Type | Purpose |
|---|---|---|
| `id` | BIGINT PK AUTO_INCREMENT | Internal primary key |
| `sku_id` | VARCHAR(64) UNIQUE | Business identifier, used as Kafka partition key |
| `name` | VARCHAR(128) | Product name |
| `price` | DECIMAL(10,2) | Unit price |
| `stock` | INT | **Inventory count** — the core field for atomic deduction |
| `description` | VARCHAR(512) | Product description |
| `image_url` | VARCHAR(256) | Image URL |
| `created_at` / `updated_at` | DATETIME | Timestamps |

**Critical design**: `stock` is a plain integer field. Concurrency safety is achieved purely through the atomic `UPDATE ... WHERE stock >= qty` SQL, not through application-level locking.

##### `t_order` — Order Table

| Column | Type | Purpose |
|---|---|---|
| `order_no` | VARCHAR(64) UNIQUE | Client-facing order number (UUID-based) |
| `sku_id` | VARCHAR(64) | Which product was ordered |
| `quantity` | INT | Order quantity |
| `amount` | DECIMAL(10,2) | Total price (price × quantity) |
| `status` | TINYINT | `0`=pending, `1`=completed, `2`=failed |

##### `t_outbox` — Transactional Outbox Table

| Column | Type | Purpose |
|---|---|---|
| `aggregate_type` | VARCHAR(64) | Entity type (e.g., "ORDER") |
| `aggregate_id` | VARCHAR(64) | Entity ID (e.g., orderNo) |
| `event_type` | VARCHAR(64) | Event name (e.g., "ORDER_CREATED") |
| `payload` | TEXT | Full event JSON body |
| `status` | TINYINT | `0`=pending publish, `1`=published |

**Key index**: `idx_status` on `status` column for efficient polling by `OutboxPublisher`.

##### `t_processed_event` — Idempotency Table

| Column | Type | Purpose |
|---|---|---|
| `event_id` | VARCHAR(64) **PK** | Unique event ID from Kafka message |
| `processed_at` | DATETIME | When the event was first processed |

**No auto-increment**: The primary key IS the event ID. `INSERT IGNORE` leverages the PK unique constraint for deduplication.

##### `t_promotion` — Promotion Table

| Column | Type | Purpose |
|---|---|---|
| `sku_id` | VARCHAR(64) | Associated product |
| `label` | VARCHAR(128) | Display label (e.g., "Limited time 20% off") |
| `discount` | DECIMAL(3,2) | Discount rate (0.80 = 20% off) |
| `start_time` / `end_time` | DATETIME | Promotion validity window |

Exists primarily to demonstrate CompletableFuture parallel queries — it's a separate data source that can be queried concurrently with product info and stock.

**Seed data**: 3 products (iPhone 15 Pro, MacBook Air M3, AirPods Pro 2) and 2 promotions.

---

### 3.3 Entity Layer

All entities use:
- `@Data` (Lombok): Auto-generates getters, setters, `toString()`, `equals()`, `hashCode()`
- `@Entity` + `@Table`: JPA mapping to MySQL table
- `jakarta.persistence.*`: Spring Boot 3 namespace (not `javax.persistence`)

#### `Product.java`

Maps to `t_product`. The `stock` field is the central field for the entire write path. Note: stock is read via JPA but **deducted** via native SQL in the repository (not via JPA's dirty checking).

#### `Order.java`

Maps to `t_order`. The `status` field uses `columnDefinition = "TINYINT"` to match MySQL's TINYINT type (Hibernate defaults to INT).

#### `Outbox.java`

Maps to `t_outbox`. The `payload` field stores the full order JSON. Written in the same transaction as the order (Technique 2). The `status` field (0/1) is polled by `OutboxPublisher`.

#### `ProcessedEvent.java`

Maps to `t_processed_event`. Only has 2 fields: `eventId` (PK) and `processedAt`. This is the simplest entity but plays a critical role — its primary key constraint is what makes `INSERT IGNORE` work for idempotency.

#### `Promotion.java`

Maps to `t_promotion`. Queried by `skuId` with a time-window filter to find active promotions.

---

### 3.4 Repository Layer

All repositories extend `JpaRepository<Entity, IdType>`, getting standard CRUD for free.

#### `ProductRepository.java`

The most important repository. Contains:

```java
// Standard JPA derived query
Optional<Product> findBySkuId(String skuId);

// ★ TECHNIQUE 4: Atomic stock deduction
@Modifying
@Query("UPDATE Product p SET p.stock = p.stock - :qty WHERE p.skuId = :skuId AND p.stock >= :qty")
int deductStock(@Param("skuId") String skuId, @Param("qty") int qty);
```

**How `deductStock` prevents overselling**: The `WHERE stock >= qty` condition is evaluated atomically by MySQL's InnoDB engine while holding a row-level lock. If two concurrent threads try to deduct from stock=1, only one `UPDATE` will match the `WHERE` condition; the other gets `rows affected = 0` and knows deduction failed.

**Return value**: `1` = success (1 row affected), `0` = insufficient stock (0 rows affected).

#### `OrderRepository.java`

Simple: `findByOrderNo(String orderNo)` for order status lookup.

#### `OutboxRepository.java`

```java
List<Outbox> findTop100ByStatusOrderByIdAsc(int status);
```

Spring Data JPA derived query that translates to:
```sql
SELECT * FROM t_outbox WHERE status = ? ORDER BY id ASC LIMIT 100
```
Used by `OutboxPublisher` to batch-fetch pending events. The `Top100` prevents loading too many records at once. `OrderByIdAsc` ensures FIFO ordering.

#### `ProcessedEventRepository.java`

```java
@Modifying
@Query(value = "INSERT IGNORE INTO t_processed_event (event_id, processed_at) VALUES (:eventId, NOW())",
       nativeQuery = true)
int insertIgnore(@Param("eventId") String eventId);
```

**Why native query?** JPA/JPQL doesn't support `INSERT IGNORE` syntax. This must be a native MySQL query.

**Return value**: `1` = first time processing (row inserted), `0` = duplicate (already exists, ignored).

#### `PromotionRepository.java`

```java
List<Promotion> findBySkuIdAndStartTimeBeforeAndEndTimeAfter(String skuId, LocalDateTime now1, LocalDateTime now2);
```

Finds promotions where `start_time < now AND end_time > now` (currently active). Both `now1` and `now2` are passed the same `LocalDateTime.now()` value.

---

### 3.5 Kafka Layer

#### `OrderEvent.java` — Message Payload DTO

| Field | Type | Purpose |
|---|---|---|
| `eventId` | String | UUID for idempotency checking |
| `orderNo` | String | Client-facing order number |
| `skuId` | String | Product SKU (also used as Kafka partition key) |
| `quantity` | int | Order quantity |
| `retryCount` | int | Number of times this message has been retried |

Uses `@Data`, `@NoArgsConstructor`, `@AllArgsConstructor` (Lombok) for JSON serialization compatibility.

#### `OrderKafkaProducer.java` — Message Sender

Three methods for three topics:

| Method | Topic | When Used |
|---|---|---|
| `sendOrderEvent()` | `order-events` | Normal order placement |
| `sendToRetry()` | `order-events-retry` | Consumer fails with retryable error |
| `sendToDlq()` | `order-events-dlq` | Retry count exceeded MAX_RETRY |

**Key design**: All three methods use `event.getSkuId()` as the Kafka message key. This ensures:
1. Same SKU always goes to the same partition (hash-based)
2. Within a partition, messages are strictly ordered
3. Same SKU's orders are processed sequentially → no race conditions

The `sendOrderEvent()` method uses `.whenComplete()` (async callback) to log success/failure without blocking the caller.

#### `OrderKafkaConsumer.java` — Message Consumer

Listens to both `order-events` and `order-events-retry` topics with the same group ID. The core flow:

```
Message received
    │
    ▼
processRecord()
    │
    ├──→ Deserialize JSON to OrderEvent
    ├──→ Call orderProcessingService.processOrder(event)
    │
    ├── Success → ack.acknowledge() (commit offset)
    │
    ├── InsufficientStockException → ack + don't retry (non-retryable)
    │
    └── Other Exception
        ├── retryCount < 3 → increment retryCount, send to retry topic, ack
        └── retryCount >= 3 → send to DLQ, ack
```

**Why always `ack.acknowledge()`?** Even on failure, we acknowledge the message to prevent it from being redelivered by Kafka. Instead, we explicitly route it to the retry topic or DLQ. This gives us control over retry timing and avoids infinite redelivery loops.

#### `InsufficientStockException.java`

A custom `RuntimeException` that signals "inventory is zero/not enough." The consumer catches this specifically and does NOT retry — retrying can never fix an out-of-stock condition.

---

### 3.6 Outbox Publisher

#### `OutboxPublisher.java`

```java
@Scheduled(fixedDelay = 5000)  // Every 5 seconds
@Transactional
public void publishPendingEvents() {
    List<Outbox> pending = outboxRepo.findTop100ByStatusOrderByIdAsc(0);
    for (Outbox event : pending) {
        kafkaTemplate.send(OUTBOX_TOPIC, event.getAggregateId(), event.getPayload()).get();
        event.setStatus(1);  // Mark published
        outboxRepo.save(event);
    }
}
```

**Critical detail**: `.get()` on the Kafka send future makes it **synchronous** — we only mark the event as published AFTER Kafka confirms receipt. If Kafka is down, the event stays at status=0 and will be retried next cycle.

**Why this is safe**:
- If the app crashes after DB commit but before Kafka send → event remains status=0 → next poll picks it up
- If Kafka send succeeds but DB update fails → event stays status=0 → duplicate publish → but downstream is idempotent (Technique 3)
- Net result: **at-least-once delivery with eventual consistency**

---

### 3.7 Service Layer

#### `OrderService.java` — The Write Entry Point

**Business logic**: When a user clicks "Place Order":

1. Generate a unique `orderNo` (e.g., `ORD-A1B2C3D4E5F6`)
2. Generate a unique `eventId` (UUID) for idempotency
3. Wrap into an `OrderEvent` DTO
4. Send to Kafka via `OrderKafkaProducer` (non-blocking)
5. Return `orderNo` to the user immediately

**Key insight**: No database write happens here. The user gets a response in ~5ms (just the time to enqueue a Kafka message). The actual inventory deduction happens asynchronously. This is peak shaving — even if 10,000 requests arrive simultaneously, they all queue in Kafka and are processed at the consumer's pace.

Also provides `getOrder()` and `getAllOrders()` for the read/list endpoints.

#### `OrderProcessingService.java` — The Core Transaction

The most important class in the entire project. Executes 4 steps in a single `@Transactional` method:

```java
@Transactional
public void processOrder(OrderEvent event) {
    // Step 1: Idempotency check
    int inserted = processedEventRepo.insertIgnore(event.getEventId());
    if (inserted == 0) return;  // Already processed, skip

    // Step 2: Atomic stock deduction
    int rows = productRepo.deductStock(event.getSkuId(), event.getQuantity());
    if (rows == 0) throw new InsufficientStockException(event.getSkuId());

    // Step 3: Create order record
    Order order = new Order();
    // ... set fields ...
    order.setStatus(1);  // Completed
    orderRepo.save(order);

    // Step 4: Write outbox event (same transaction!)
    Outbox outbox = new Outbox();
    // ... set fields ...
    outbox.setStatus(0);  // Pending publish
    outboxRepo.save(outbox);
}
```

**Why single transaction matters**: If step 3 succeeds but step 4 fails (or vice versa), the entire transaction rolls back. This guarantees that either ALL of (idempotency record + stock deduction + order + outbox) happen, or NONE happen.

#### `ProductService.java` — Product CRUD

Standard CRUD service with one important detail: **after any write operation (update/delete), it calls `cacheService.evict(skuId)`** to invalidate the Redis cache. This prevents stale data being served from cache.

Strategy: **Cache-Aside (Lazy Invalidation)**
- Read: check cache → miss → load from DB → write cache
- Write: update DB → delete cache (next read will reload)

#### `ProductCacheService.java` — Redis Cache with Anti-Patterns Protection

The most technically dense service. Implements three protections:

**1. Mutex Lock (Anti-Stampede / Anti-Cache-Breakdown)**:

When cache expires for a hot key and 100 concurrent requests arrive:
- WITHOUT mutex: all 100 hit DB simultaneously → DB overload
- WITH mutex: 1 request acquires lock, queries DB, writes cache; 99 others wait 50ms then read from cache

Implementation: Redis `SET NX EX` (set if not exists, with expiry) as a distributed lock.

**2. TTL Jitter (Anti-Avalanche)**:

If all product caches expire at the same time (same TTL), a massive wave of DB queries hits simultaneously.

Solution: `actualTTL = baseTTL + random(-jitter, +jitter)` = `300 + random(-60, 60)` = between 240s and 360s. This spreads cache expiry across a time window.

**3. Null Value Caching (Anti-Penetration)**:

If someone queries a non-existent skuId, every request goes to DB (cache never helps).

Solution: Cache the string `"NULL"` for 60s. Next request sees `"NULL"` in cache and returns empty immediately without hitting DB.

#### `ProductDetailService.java` — CompletableFuture Aggregation

Demonstrates how to parallelize independent queries:

```
Serial execution:    |--product 200ms--|--stock 100ms--|--promo 150ms--| = 450ms total
Parallel execution:  |--product 200ms--|
                     |--stock 100ms----|
                     |--promo 150ms----|                                = 200ms total (max)
```

Three `CompletableFuture.supplyAsync()` calls execute on the `queryExecutor` thread pool. Each has an independent timeout:

| Query | Timeout | Degradation Fallback |
|---|---|---|
| Product info (Redis) | 500ms | `degradeInfo.product = "基本信息暂不可用"` |
| Realtime stock (DB) | 300ms | Use cached stock value |
| Promotions (DB) | 300ms | Return empty promotion list |

The `ProductDetailVO` includes a `degradeInfo` map. If non-null, the frontend displays a yellow warning banner showing which data sources degraded.

---

### 3.8 Controller Layer

#### `ProductController.java`

| Endpoint | Method | Handler | Technique |
|---|---|---|---|
| `GET /api/products` | List all | `productService.listAll()` | — |
| `GET /api/products/{skuId}` | Detail | `productDetailService.getProductDetail()` | #5 CompletableFuture, #6 Redis |
| `POST /api/products` | Create | `productService.create()` | — |
| `PUT /api/products/{skuId}` | Update | `productService.update()` | Cache eviction |
| `DELETE /api/products/{skuId}` | Delete | `productService.delete()` | Cache eviction |

#### `OrderController.java`

| Endpoint | Method | Handler | Technique |
|---|---|---|---|
| `POST /api/orders` | Place order | `orderService.placeOrder()` | #1 Kafka peak shaving |
| `GET /api/orders/{orderNo}` | Get order | `orderService.getOrder()` | — |
| `GET /api/orders` | List all | `orderService.getAllOrders()` | — |

---

### 3.9 Frontend (React + Vite)

#### `vite.config.js`

```js
server: {
  port: 5173,
  proxy: { '/api': 'http://localhost:8080' }  // Proxy API calls to backend
}
```

The proxy eliminates CORS issues in development. All `/api/*` requests from the browser go to Vite (port 5173), which forwards them to Spring Boot (port 8080).

#### `src/api/index.js`

Thin wrapper around `fetch()` for all 5 API calls:
- `fetchProducts()` → `GET /api/products`
- `fetchProductDetail(skuId)` → `GET /api/products/{skuId}`
- `placeOrder(skuId, qty)` → `POST /api/orders`
- `fetchOrders()` → `GET /api/orders`
- `fetchOrder(orderNo)` → `GET /api/orders/{orderNo}`

#### `src/main.jsx`

Application shell with:
- `BrowserRouter` for client-side routing
- Navigation bar with links to Product List and Order List
- Three routes: `/` (products), `/product/:skuId` (detail), `/orders`

#### `src/components/ProductList.jsx`

Fetches all products on mount, displays as a responsive card grid. Clicking a card navigates to the detail page. Shows name, price, stock count, and SKU ID.

#### `src/components/ProductDetail.jsx`

The most feature-rich frontend component:
- Calls `fetchProductDetail(skuId)` which hits the CompletableFuture aggregation endpoint
- Displays product info, realtime stock, promotion tags
- **Degradation warning**: If `degradeInfo` is present in the response, shows a yellow banner explaining which data sources timed out
- **Order form**: Quantity input + "Place Order" button
- After ordering: shows toast notification with order number, refreshes stock
- **Tech explanation panel**: Gray box at the bottom explaining which backend techniques are in play

#### `src/components/OrderList.jsx`

Simple order table with columns: Order No, SKU, Quantity, Amount, Status, Created Time. Status is color-coded:
- Yellow: Processing (status=0)
- Green: Completed (status=1)
- Red: Failed (status=2)

Includes a "Refresh" button to re-fetch orders (useful since order processing is async — the order may appear as "processing" initially, then "completed" after Kafka consumes it).

#### `src/index.css`

Global styles: dark navbar, card grid layout, table styles, toast notification with slide-in animation, promotion tags, degradation warning box. Clean, minimal design focused on functionality.

---

## 4. The 6 Core Techniques — Deep Dive

### Technique 1: Peak Shaving with Kafka

**Problem**: In a flash sale, thousands of order requests arrive simultaneously. If each request directly writes to MySQL, the database becomes a bottleneck (connection pool exhausted, lock contention, timeouts).

**Solution**: Decouple request acceptance from processing. The API endpoint only enqueues a Kafka message and returns immediately. The actual heavy work (DB transactions) happens asynchronously at a controlled rate.

```
         Without Kafka                              With Kafka
  ┌──────────────────────┐                  ┌──────────────────────┐
  │    10,000 req/s      │                  │    10,000 req/s      │
  │         │            │                  │         │            │
  │         ▼            │                  │         ▼            │
  │  ┌──────────────┐   │                  │  ┌──────────────┐   │
  │  │   MySQL DB   │   │                  │  │    Kafka     │   │
  │  │  (overloaded)│   │                  │  │  (buffered)  │   │
  │  └──────────────┘   │                  │  └──────┬───────┘   │
  │  Response: 5000ms   │                  │         │           │
  │  Errors: many       │                  │    500 msg/s        │
  └──────────────────────┘                  │         ▼           │
                                            │  ┌──────────────┐  │
                                            │  │   MySQL DB   │  │
                                            │  │  (steady)    │  │
                                            │  └──────────────┘  │
                                            │  Response: 5ms     │
                                            │  Errors: 0         │
                                            └──────────────────────┘
```

**Files involved**:
- `OrderService.java:50-63` — Sends OrderEvent to Kafka
- `OrderKafkaProducer.java:37-54` — `kafkaTemplate.send(topic, key, json)`
- `OrderKafkaConsumer.java:34-37` — `@KafkaListener` consumes at its own pace

---

### Technique 2: Transactional Outbox for Eventual Consistency

**Problem**: After placing an order, you need to notify downstream systems (analytics, shipping, notifications). A naive approach sends a Kafka message after the DB commit. But what if the app crashes between commit and send? The order exists in DB but the notification is lost.

**Solution**: Write the event to an `t_outbox` table **inside the same DB transaction** as the business data. A background job polls this table and publishes to Kafka. Even if the app crashes, the event persists in the DB and will be published on restart.

```
  ┌──────────────────────────────────────────────────────┐
  │              Single MySQL Transaction                 │
  │                                                       │
  │  INSERT INTO t_order (...)        ← Business data     │
  │  INSERT INTO t_outbox (...)       ← Event record      │
  │                                                       │
  │  COMMIT  ← Both succeed or both fail (ACID)           │
  └──────────────────────────────────────────────────────┘
                          │
                          ▼ (later, asynchronously)
  ┌──────────────────────────────────────────────────────┐
  │  OutboxPublisher (@Scheduled every 5s)                │
  │                                                       │
  │  SELECT * FROM t_outbox WHERE status=0 LIMIT 100      │
  │  For each: send to Kafka → UPDATE status=1            │
  └──────────────────────────────────────────────────────┘
```

**Files involved**:
- `OrderProcessingService.java:72-85` — Writes outbox in same transaction
- `Outbox.java` — Entity with status field
- `OutboxPublisher.java:38-61` — Scheduled poller
- `OutboxRepository.java:10` — `findTop100ByStatusOrderByIdAsc(0)`

---

### Technique 3: Idempotent Processing with INSERT IGNORE

**Problem**: Kafka guarantees at-least-once delivery. If the consumer processes a message but crashes before committing the offset, Kafka redelivers the same message. Without idempotency, the same order could deduct inventory twice.

**Solution**: Each message carries a unique `eventId`. Before processing, we `INSERT IGNORE` into `t_processed_event`. If the eventId already exists (duplicate), the INSERT is silently ignored and returns 0 — we skip processing.

```
  Message A (eventId="abc-123") arrives
      │
      ▼
  INSERT IGNORE INTO t_processed_event (event_id) VALUES ('abc-123')
      │
      ├── Returns 1 (first time) → Proceed with processing
      │
      └── Returns 0 (duplicate) → Skip, already processed

  This is inside the same transaction as stock deduction,
  so either BOTH the idempotency record and stock change persist,
  or NEITHER does.
```

**Files involved**:
- `ProcessedEventRepository.java:17-18` — `INSERT IGNORE` native query
- `OrderProcessingService.java:43-49` — Check result, skip if 0
- `OrderEvent.java:41` — `eventId` field

---

### Technique 4: Atomic Inventory Deduction + Partition Ordering

**Problem**: Two orders for the same SKU arrive at the same time. If both read stock=1, both think there's enough, and both deduct — resulting in stock=-1 (oversold).

**Solution (Part A — Atomic SQL)**:

```sql
UPDATE t_product SET stock = stock - :qty WHERE sku_id = :skuId AND stock >= :qty
```

MySQL InnoDB executes this atomically with a row-level lock. The `stock >= qty` check and the `stock = stock - qty` update happen as one operation. Two concurrent UPDATEs on the same row are serialized by InnoDB.

**Solution (Part B — Partition Ordering)**:

Kafka messages use `skuId` as the message key. Kafka guarantees that all messages with the same key go to the same partition, and messages within a partition are consumed in order. So for the same SKU, orders are processed one-by-one, eliminating concurrency issues entirely at the consumer level.

```
  Producer sends: key="SKU001" → always goes to partition 3 (hash-based)
  Producer sends: key="SKU002" → always goes to partition 7

  Partition 3: [order-A for SKU001] [order-B for SKU001] [order-C for SKU001]
                    ↑ processed first    ↑ second              ↑ third

  No concurrent processing of the same SKU = no race condition
```

**Files involved**:
- `ProductRepository.java:21-22` — `deductStock()` with WHERE clause
- `OrderKafkaProducer.java:41` — `kafkaTemplate.send(topic, event.getSkuId(), json)` — key=skuId
- `KafkaTopicConfig.java:32-35` — 12 partitions

---

### Technique 5: CompletableFuture Concurrent Aggregation + Timeout Degradation

**Problem**: A product detail page needs data from 3 sources. Sequential queries: 200ms + 100ms + 150ms = 450ms. Too slow for high-traffic pages.

**Solution**: Fire all 3 queries in parallel using `CompletableFuture.supplyAsync()`. Total latency = max(200, 100, 150) = 200ms. If any query is slow or fails, degrade gracefully instead of failing the entire request.

```
  Thread Pool (8 threads)
      │
      ├──→ Future 1: ProductCacheService.getBySkuId()   [Redis → DB fallback]
      │         timeout: 500ms
      │         degrade: "基本信息暂不可用"
      │
      ├──→ Future 2: ProductRepository.findBySkuId()    [Direct DB for realtime]
      │         timeout: 300ms
      │         degrade: use cached stock value
      │
      └──→ Future 3: PromotionRepository.find...()      [Direct DB]
               timeout: 300ms
               degrade: empty promotion list

  All three execute simultaneously on the queryExecutor thread pool.
  Results are collected with individual timeouts.
```

**Files involved**:
- `ProductDetailService.java:58-116` — The entire aggregation logic
- `AsyncConfig.java:16-18` — Thread pool bean
- `ProductDetailService.ProductDetailVO` — Response DTO with `degradeInfo` map

---

### Technique 6: Redis Cache — Anti-Stampede / Anti-Avalanche

**Problem 1 — Cache Breakdown (击穿)**: A single hot key expires. 10,000 concurrent requests all miss cache simultaneously and flood the database.

**Solution**: Mutex lock. Only one thread queries DB and rebuilds cache. Others wait briefly then read from the freshly populated cache.

**Problem 2 — Cache Avalanche (雪崩)**: Many keys expire at the same time (e.g., all set with TTL=300s at the same time). Massive DB load spike.

**Solution**: Add random jitter to TTL. `actualTTL = 300 ± random(60)` seconds. Keys expire at different times, spreading DB load.

**Problem 3 — Cache Penetration (穿透)**: Queries for non-existent keys always miss cache and hit DB.

**Solution**: Cache the null result as a special sentinel value `"NULL"` with short TTL (60s).

```
  Request for skuId="SKU001"
      │
      ▼
  Redis GET "product:SKU001"
      │
      ├── HIT (valid JSON) → deserialize, return                    ← Happy path
      │
      ├── HIT ("NULL") → return empty (anti-penetration)            ← Known non-existent
      │
      └── MISS → Try to acquire mutex lock
                    │
                    ├── GOT LOCK:
                    │     Double-check cache (another thread may have just filled it)
                    │     Query MySQL
                    │     ├── Found → SET cache with jittered TTL → release lock → return
                    │     └── Not found → SET "NULL" with 60s TTL → release lock → return empty
                    │
                    └── NO LOCK (another thread holds it):
                          Sleep 50ms
                          Retry Redis GET
                          ├── HIT → return
                          └── MISS → Fallback: query DB directly
```

**Files involved**:
- `ProductCacheService.java:45-65` — Main `getBySkuId()` method
- `ProductCacheService.java:71-126` — `loadWithMutex()` with lock, double-check, jitter
- `ProductCacheService.java:129-132` — `evict()` called after writes

---

## 5. Advanced Topics: Kafka Retry & DLQ

### Retry Strategy Classification

```
  Consumer receives message
      │
      ├── Process succeeds → ack → done
      │
      ├── InsufficientStockException (non-retryable)
      │     → ack → don't retry (retrying can't fix "no stock")
      │
      └── Other Exception (retryable: DB timeout, network error, etc.)
            │
            ├── retryCount < 3
            │     → increment retryCount
            │     → send to order-events-retry topic
            │     → ack original message
            │
            └── retryCount >= 3
                  → send to order-events-dlq (dead letter queue)
                  → ack original message
                  → requires manual intervention
```

### Topic Partition Count Selection

| Count | Use Case |
|---|---|
| 12 | Small-to-medium (up to 12 consumer instances) |
| 24 | Medium (common production default) |
| 48 | Large scale (high throughput requirements) |

**Hot SKU partition bottleneck**: If one SKU gets 90% of traffic, its partition becomes a bottleneck while other partitions are idle. Solutions:
1. **Sub-partitioning within a partition**: Add a random suffix to the key (e.g., `SKU001-0` through `SKU001-9`) to spread across 10 partitions. Consumer aggregates.
2. **Dedicated topic for hot SKUs**: Route hot SKUs to a separate high-throughput topic.

---

## 6. Data Flow Diagrams

### Write Path: Place Order

```
User clicks "Place Order"
    │
    ▼
[Frontend] POST /api/orders { skuId: "SKU001", quantity: 1 }
    │
    ▼
[OrderController] → [OrderService.placeOrder()]
    │
    ├── Generate orderNo = "ORD-A1B2C3D4E5F6"
    ├── Generate eventId = UUID
    ├── Build OrderEvent { eventId, orderNo, skuId, quantity, retryCount=0 }
    ├── kafkaProducer.sendOrderEvent(event)  ← async, non-blocking
    │       │
    │       └──→ Kafka topic "order-events", key="SKU001", partition=hash("SKU001")%12
    │
    └── Return { orderNo, message: "Order submitted" }  ← user sees this in ~5ms

    ... later (typically within 100ms) ...

[OrderKafkaConsumer] picks up message from "order-events"
    │
    ▼
[OrderProcessingService.processOrder()] — SINGLE TRANSACTION
    │
    ├── INSERT IGNORE t_processed_event (eventId)  → returns 1 (first time)
    ├── UPDATE t_product SET stock=stock-1 WHERE skuId='SKU001' AND stock>=1  → returns 1
    ├── INSERT t_order (orderNo, skuId, qty=1, amount=7999.00, status=1)
    ├── INSERT t_outbox (type='ORDER', id=orderNo, event='ORDER_CREATED', payload=JSON)
    └── COMMIT

    ... 5 seconds later ...

[OutboxPublisher] polls t_outbox
    │
    ├── SELECT * FROM t_outbox WHERE status=0 LIMIT 100
    ├── For each: kafkaTemplate.send("outbox-events", orderNo, payload).get()
    ├── UPDATE t_outbox SET status=1
    └── (downstream services consume "outbox-events" topic)
```

### Read Path: Product Detail

```
User clicks a product card
    │
    ▼
[Frontend] GET /api/products/SKU001
    │
    ▼
[ProductController] → [ProductDetailService.getProductDetail("SKU001")]
    │
    ├── CompletableFuture #1 (thread from queryExecutor pool):
    │       ProductCacheService.getBySkuId("SKU001")
    │       │
    │       ├── Redis GET "product:SKU001"
    │       │   ├── HIT → return cached Product
    │       │   └── MISS → acquire lock → query DB → write cache with jittered TTL → return
    │       │
    │       └── (timeout 500ms, degrade: product = null + warning)
    │
    ├── CompletableFuture #2 (another thread):
    │       productRepo.findBySkuId("SKU001").map(Product::getStock)
    │       │
    │       └── Direct DB query for realtime stock (not cached!)
    │       └── (timeout 300ms, degrade: use cached stock)
    │
    └── CompletableFuture #3 (another thread):
            promotionRepo.findBySkuIdAndStartTimeBeforeAndEndTimeAfter(...)
            │
            └── Query active promotions
            └── (timeout 300ms, degrade: empty list)

    Aggregate into ProductDetailVO { product, realtimeStock, promotions, degradeInfo }
    │
    ▼
[Frontend] renders:
    ├── Product name, price, description
    ├── Realtime stock count
    ├── Promotion tags (yellow badges)
    └── Degradation warning (if any query timed out)
```

---

## 7. API Reference

### Products

| Method | Path | Request Body | Response | Description |
|---|---|---|---|---|
| GET | `/api/products` | — | `Product[]` | List all products |
| GET | `/api/products/{skuId}` | — | `ProductDetailVO` | Product detail with realtime stock + promotions |
| POST | `/api/products` | `Product` JSON | `Product` | Create new product |
| PUT | `/api/products/{skuId}` | `Product` JSON | `Product` | Update product (evicts cache) |
| DELETE | `/api/products/{skuId}` | — | 204 No Content | Delete product (evicts cache) |

### Orders

| Method | Path | Request Body | Response | Description |
|---|---|---|---|---|
| POST | `/api/orders` | `{ skuId, quantity }` | `{ orderNo, message }` | Place order (async via Kafka) |
| GET | `/api/orders/{orderNo}` | — | `Order` | Get order by order number |
| GET | `/api/orders` | — | `Order[]` | List all orders |

### Response Schemas

**ProductDetailVO**:
```json
{
  "product": { "id": 1, "skuId": "SKU001", "name": "iPhone 15 Pro", "price": 7999.00, "stock": 98, ... },
  "realtimeStock": 98,
  "promotions": [ { "label": "Limited time -500", "discount": 0.94 } ],
  "degradeInfo": null   // or { "stock": "Stock data may be delayed" } if degraded
}
```

---

## 8. How to Run

### Prerequisites

- Docker Desktop (for MySQL, Redis, Kafka)
- JDK 17+ (e.g., Eclipse Temurin)
- Maven 3.9+
- Node.js 18+ (for frontend)

### Step 1: Start Infrastructure

```bash
docker-compose up -d
```

Wait ~15 seconds for all services to initialize. Verify:
```bash
docker ps   # Should show 3 running containers
```

### Step 2: Start Backend

```bash
cd inventory-service
mvn spring-boot:run
```

Or on Windows with the helper script:
```bash
node start-backend.cjs
```

Backend starts on `http://localhost:8080`.

### Step 3: Start Frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend starts on `http://localhost:5173`.

### Step 4: Verify

1. Open `http://localhost:5173` — you should see 3 product cards
2. Click a product → see detail page with realtime stock and promotions
3. Click "Place Order" → see toast with order number
4. Go to "Orders" tab → see the order with status "Completed"
5. Check backend logs → see Kafka produce/consume, cache hits, transaction logs

---

> **For the Chinese overview README, see [README.md](../README.md).**
> **For the bilingual technical PRD, see [TECHNICAL_PRD.md](./TECHNICAL_PRD.md).**
