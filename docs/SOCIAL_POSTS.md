# Social Media Posts / 社交媒体投稿

---

## Twitter / X

### English Version

---

**Post 1 (Main thread starter)**

I built a minimal Spring Boot 3 + React project that covers 6 backend techniques you'd see in large-scale systems — all in one simple "place order & deduct inventory" scenario.

Open source, lightweight, easy to read. Built for learning.

🔗 https://github.com/GChenSi-2/SimpleHighPerformanceBackendTechniquesDisplay

🧵 What's inside ↓

---

**Post 2 (Thread)**

The 6 techniques covered:

1⃣ Kafka peak shaving — async order processing, no sync DB blocking
2⃣ Transactional Outbox — eventual consistency without dual-write
3⃣ Idempotency — INSERT IGNORE deduplication
4⃣ Atomic inventory deduction — no overselling under concurrency
5⃣ CompletableFuture fan-out — parallel queries + timeout degradation
6⃣ Redis cache — mutex lock anti-stampede + TTL jitter anti-avalanche

---

**Post 3 (Thread)**

Why I built this:

Most "high concurrency" tutorials explain theory but skip runnable code.

This project is the opposite — every technique maps to a real file you can read, run, and debug locally.

One `docker-compose up -d` gives you MySQL + Redis + Kafka. Then just `mvn spring-boot:run`.

---

**Post 4 (Thread)**

Bonus topics covered:
• Kafka retry topic + Dead Letter Queue (DLQ)
• Partition key design (skuId → same partition → ordered processing)
• Cache penetration / breakdown / avalanche — all 3 anti-patterns handled

Full docs in 3 languages: 中文 / English / 日本語

Perfect for interview prep or weekend learning 🍵

---

### 中文版

---

**推文 1（主贴）**

做了一个极简的 Spring Boot 3 + React 开源项目，用一个「下单扣库存」的场景覆盖了 6 个大规模系统的后端核心技术。

轻量、可运行、每个技术点对应一个文件，适合项目式学习。

🔗 https://github.com/GChenSi-2/SimpleHighPerformanceBackendTechniquesDisplay

🧵 技术点 ↓

---

**推文 2（Thread）**

覆盖的 6 个技术点：

1⃣ Kafka 削峰填谷 — 写请求异步，不阻塞 DB
2⃣ Outbox 模式 — 事务内写发件箱，最终一致
3⃣ INSERT IGNORE 幂等 — 重复消息不重复扣库存
4⃣ 原子 SQL 扣库存 — WHERE stock >= qty 防超卖
5⃣ CompletableFuture 并发聚合 — 3路并发 + 超时降级
6⃣ Redis 缓存三防 — 互斥锁防击穿 / TTL 抖动防雪崩 / 空值防穿透

---

**推文 3（Thread）**

为什么做这个？

网上大规模系统的教程很多讲理论，但缺可运行的最小实现。

这个项目反过来 —— 每个技术点都对应具体的 Java 文件，本地 docker-compose up 就能跑。

附带中文/英文/日文三语文档，面试复习或周末学习都合适 🍵

---

### 日本語版

---

**ツイート 1（メイン）**

Spring Boot 3 + React のミニマルなOSSプロジェクトを作りました。「注文＆在庫引当」という1つのシナリオで、大規模システムのバックエンド技術6つを網羅しています。

軽量・実行可能・ファイル単位で技術が対応。学習用に最適。

🔗 https://github.com/GChenSi-2/SimpleHighPerformanceBackendTechniquesDisplay

🧵 技術ポイント ↓

---

**ツイート 2（Thread）**

カバーする6つの技術：

1⃣ Kafkaピーク削減 — 非同期注文処理
2⃣ Outboxパターン — 結果整合性
3⃣ INSERT IGNORE冪等性 — メッセージ重複排除
4⃣ アトミック在庫引当 — 過剰販売防止
5⃣ CompletableFuture並列集約 — タイムアウトデグレード付き
6⃣ Redisキャッシュ三重防御 — スタンピード/雪崩/ペネトレーション対策

中文/English/日本語の3言語ドキュメント付き 🍵

---

---

## LinkedIn

### English Version

---

**I built an open-source project to make high-concurrency backend techniques actually learnable.**

When I was studying large-scale system design, I found plenty of theory — but very few minimal, runnable codebases that I could actually step through and debug.

So I built one.

**SimpleHighPerformanceBackendTechniquesDisplay** is a Spring Boot 3 + React project that uses a single business scenario — placing an order and deducting inventory — to demonstrate 6 core backend engineering patterns:

**① Kafka Peak Shaving** — Write requests go to Kafka first, consumed asynchronously. No synchronous DB blocking under traffic spikes.

**② Transactional Outbox** — Business data and domain events are written in the same MySQL transaction. A background poller publishes to Kafka. This guarantees eventual consistency without dual-write problems.

**③ Idempotent Processing** — `INSERT IGNORE` on a processed-event table ensures duplicate Kafka messages never deduct inventory twice.

**④ Atomic Inventory Deduction** — `UPDATE stock = stock - qty WHERE stock >= qty` prevents overselling at the database level. Combined with Kafka partition-key routing (key = skuId), same-SKU orders are processed sequentially.

**⑤ CompletableFuture Parallel Aggregation** — Product detail pages fan out 3 queries concurrently (product info from Redis, realtime stock from DB, promotions from DB), each with independent timeouts and graceful degradation.

**⑥ Redis Cache Strategy** — Mutex lock prevents cache stampede on hot keys. TTL jitter prevents cache avalanche. Null-value caching prevents cache penetration.

**Bonus:** Kafka retry topic + dead-letter queue (DLQ) with classified error handling (retryable vs. non-retryable exceptions).

**What makes this different:**
- Every technique maps to a specific, readable Java file
- One `docker-compose up -d` starts MySQL + Redis + Kafka
- Full documentation in 3 languages (Chinese / English / Japanese)
- Designed to be read in a weekend, not a semester

Whether you're preparing for system design interviews or simply want to see these patterns in action — feel free to clone, run, and explore.

🔗 **GitHub:** https://github.com/GChenSi-2/SimpleHighPerformanceBackendTechniquesDisplay

Tech stack: Spring Boot 3.2.5 · Java 17 · MySQL 8 · Apache Kafka (KRaft) · Redis 7 · React 18 · Docker Compose

#SystemDesign #BackendEngineering #SpringBoot #Kafka #Redis #OpenSource #SoftwareEngineering #LearningInPublic

---

### 中文版

---

**我做了一个开源项目，让高并发后端技术真正「可学」。**

在学习大规模系统设计时，我发现理论资料很多，但极少有能直接运行、逐文件阅读调试的最小实现。

所以我做了一个。

**SimpleHighPerformanceBackendTechniquesDisplay** 是一个 Spring Boot 3 + React 项目，用「下单扣库存」这一个业务场景，演示 6 个核心后端技术：

**① Kafka 削峰填谷** — 写请求先进 Kafka 队列，异步消费，避免流量高峰时 DB 被打爆。

**② Outbox 最终一致** — 业务表和事件在同一个 MySQL 事务内写入。后台轮询发布到 Kafka，保证最终一致性，杜绝双写问题。

**③ INSERT IGNORE 幂等** — 消费端通过幂等表去重，确保 Kafka 重复投递不会重复扣库存。

**④ 原子扣库存 + 分区顺序** — `UPDATE stock=stock-qty WHERE stock>=qty` 防超卖；key=skuId 让同一 SKU 的订单在 Kafka 分区内顺序处理。

**⑤ CompletableFuture 并发聚合** — 商品详情页 3 路并发查询（Redis 缓存 / DB 实时库存 / 促销信息），各自超时独立降级。

**⑥ Redis 缓存三防** — 互斥锁防击穿、TTL 随机抖动防雪崩、空值缓存防穿透。

**额外覆盖：** Kafka 重试 topic + 死信队列（DLQ），区分可重试 / 不可重试异常。

**这个项目的特点：**
- 每个技术点对应一个具体的 Java 文件，清晰可读
- 一条 `docker-compose up -d` 启动全部依赖
- 中文 / 英文 / 日文三语文档
- 设计目标是一个周末就能读完，而不是一个学期

无论你是在准备系统设计面试，还是想亲手跑通这些高并发模式 —— 欢迎 clone、运行、探索。

🔗 **GitHub：** https://github.com/GChenSi-2/SimpleHighPerformanceBackendTechniquesDisplay

技术栈：Spring Boot 3.2.5 · Java 17 · MySQL 8 · Apache Kafka (KRaft) · Redis 7 · React 18 · Docker Compose

#系统设计 #后端开发 #SpringBoot #Kafka #Redis #开源 #高并发

---

### 日本語版

---

**高並行バックエンド技術を「本当に学べる」OSSプロジェクトを作りました。**

大規模システム設計を学ぶ中で、理論は豊富にある一方、実際にローカルで実行してデバッグできるミニマルなコードベースが少ないことに気づきました。

そこで作りました。

**SimpleHighPerformanceBackendTechniquesDisplay** は Spring Boot 3 + React のプロジェクトで、「注文＆在庫引当」という単一のビジネスシナリオで、6つのコアバックエンド技術を実演します：

**① Kafkaピーク削減** — 書込リクエストをKafkaに入れ、非同期消費。トラフィックスパイク時のDB過負荷を防止。

**② Outbox最終整合性** — ビジネスデータとイベントを同一MySQLトランザクションで書込。バックグラウンドポーラーがKafkaに発行し、結果整合性を保証。

**③ INSERT IGNORE冪等性** — 処理済みイベントテーブルで重複排除。Kafkaの再配信でも二重在庫引当を防止。

**④ アトミック在庫引当** — `UPDATE stock=stock-qty WHERE stock>=qty` で過剰販売防止。key=skuIdパーティションで同一SKU順序処理。

**⑤ CompletableFuture並列集約** — 商品詳細ページで3クエリ並列実行、個別タイムアウト＆デグレード。

**⑥ Redisキャッシュ三重防御** — 排他ロック（スタンピード防止）+ TTLジッター（雪崩防止）+ null値キャッシュ（ペネトレーション防止）。

**特徴：**
- 各技術が具体的なJavaファイルに対応、読みやすい
- `docker-compose up -d` 一発で全依存関係起動
- 中文/English/日本語の3言語ドキュメント
- 週末で読み切れる設計

🔗 **GitHub:** https://github.com/GChenSi-2/SimpleHighPerformanceBackendTechniquesDisplay

#SystemDesign #バックエンド #SpringBoot #Kafka #Redis #OSS #エンジニア

---
