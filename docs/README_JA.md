<p align="center">
  <a href="../README.md"><img src="https://img.shields.io/badge/lang-中文-red?style=for-the-badge" alt="中文"></a>
  <a href="./README_EN.md"><img src="https://img.shields.io/badge/lang-English-blue?style=for-the-badge" alt="English"></a>
  <a href="./README_JA.md"><img src="https://img.shields.io/badge/lang-日本語-green?style=for-the-badge" alt="日本語"></a>
</p>

<p align="center">
  <b>📖 詳細エンジニアリングガイド → <a href="./DETAILED_GUIDE.md">docs/DETAILED_GUIDE.md</a></b><br>
  <b>📋 技術 PRD（バイリンガル） → <a href="./TECHNICAL_PRD.md">docs/TECHNICAL_PRD.md</a></b>
</p>

---

# 商品在庫フラッシュセールシステム — バックエンド技術学習プロジェクト

**Spring Boot 3 + React** を使用したミニマルなプロジェクトです。**商品閲覧 → 詳細表示 → 注文 → 在庫引当** という単一のビジネスシナリオで、高並行・大規模システム構築に必要な **6つのコアバックエンド技術** を網羅します。

## アーキテクチャ概要

```
┌──────────┐     POST /api/orders      ┌──────────────┐
│  React   │ ──────────────────────────→│  Spring Boot │
│ Frontend │                            │  Controller  │
└──────────┘                            └──────┬───────┘
                                               │
                              ┌────────────────↓────────────────┐
                              │         Kafka Producer           │
                              │  key=skuId → 同一SKUは同一       │
                              │  パーティション（順序処理保証）      │
                              └────────────────┬────────────────┘
                                               │
                              ┌────────────────↓────────────────┐
                              │         Kafka Consumer           │
                              │  （非同期消費、ピーク削減）         │
                              └────────────────┬────────────────┘
                                               │
                    ┌──────────────────────────────────────────────┐
                    │        単一MySQLトランザクション内で完了          │
                    │                                              │
                    │  1. INSERT IGNORE t_processed_event（冪等性） │
                    │  2. UPDATE t_product SET stock=stock-1       │
                    │     WHERE stock>=1（アトミック在庫引当）        │
                    │  3. INSERT t_order（注文作成）                 │
                    │  4. INSERT t_outbox（Outboxイベント）          │
                    └──────────────────────────────────────────────┘
                                               │
                              ┌────────────────↓────────────────┐
                              │  Outbox バックグラウンド発行        │
                              │  (@Scheduled) status=0 スキャン   │
                              │  → Kafka送信 → status=1 に更新    │
                              └─────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────┐
  │  GET /api/products/{skuId}  商品詳細（読取パス）              │
  │                                                             │
  │  CompletableFuture.supplyAsync で3クエリを並列実行:          │
  │    ① 商品情報（Redisキャッシュ → 排他ロック → TTLジッター）    │
  │    ② リアルタイム在庫（DB直接参照）                           │
  │    ③ プロモーション情報（DB参照）                             │
  │  各クエリに個別タイムアウト設定、超過時はデグレード応答         │
  └─────────────────────────────────────────────────────────────┘
```

## 6つのコア技術

| # | 技術 | 主要ファイル | 内容 |
|---|------|------------|------|
| 1 | **ピーク削減**（Kafka非同期） | `OrderKafkaProducer.java` / `OrderKafkaConsumer.java` | 注文 → Kafka → 非同期消費、同期DBブロッキング回避 |
| 2 | **Outbox最終整合性** | `OrderProcessingService.java` / `OutboxPublisher.java` | 同一TX内でOutbox書込、バックグラウンドで定期発行 |
| 3 | **冪等性**（INSERT IGNORE） | `ProcessedEventRepository.java` / `OrderProcessingService.java` | `INSERT IGNORE t_processed_event` でメッセージ重複排除 |
| 4 | **アトミック在庫引当 + パーティション順序** | `ProductRepository.java` / `OrderKafkaProducer.java` | `stock=stock-1 WHERE stock>=1` + key=skuIdパーティショニング |
| 5 | **CompletableFuture並列読取** | `ProductDetailService.java` | 3つの非同期クエリ + クエリ毎のタイムアウトデグレード |
| 6 | **Redisキャッシュ**（スタンピード/雪崩防止） | `ProductCacheService.java` | 排他ロック + TTLジッター + null値キャッシュ |

**追加：** Kafkaリトライtopic + DLQデッドレターキュー → `OrderKafkaConsumer.java` を参照

## クイックスタート

### 1. インフラ起動（MySQL + Redis + Kafka）

```bash
docker-compose up -d
```

### 2. バックエンド起動

```bash
cd inventory-service
mvn spring-boot:run
```

### 3. フロントエンド起動

```bash
cd frontend
npm install
npm run dev
```

http://localhost:5173 にアクセス

## 技術スタック

| レイヤー | 技術 | バージョン |
|---------|------|-----------|
| フレームワーク | Spring Boot | 3.2.5 |
| 言語 | Java | 17 |
| ORM | Spring Data JPA + Hibernate 6 | — |
| データベース | MySQL | 8.0 |
| メッセージキュー | Apache Kafka（KRaftモード） | 3.7.0 |
| キャッシュ | Redis | 7 |
| フロントエンド | React + Vite | 18.3 / 5.2 |
| コンテナ | Docker Compose | 3.8 |

## プロジェクト構成

```
BackendExample/
├── docker-compose.yml          # ワンコマンドでインフラ起動: MySQL/Redis/Kafka
├── sql/schema.sql              # DBスキーマ + テストデータ
├── inventory-service/          # Spring Boot バックエンド
│   └── src/main/java/com/example/inventory/
│       ├── config/             # Kafkaトピック、Redis、非同期スレッドプール、CORS
│       ├── entity/             # JPAエンティティ: Product, Order, Outbox, ProcessedEvent, Promotion
│       ├── repository/         # データアクセス（アトミック在庫SQL、INSERT IGNORE）
│       ├── kafka/              # Producer / Consumer / イベントモデル
│       ├── outbox/             # Outboxバックグラウンド定期発行
│       ├── service/            # ビジネスロジック
│       │   ├── OrderService.java           # 注文 → Kafka送信
│       │   ├── OrderProcessingService.java # Consumer核心（冪等+在庫引当+Outbox）
│       │   ├── ProductCacheService.java    # Redisキャッシュ（排他ロック+TTLジッター）
│       │   ├── ProductDetailService.java   # CompletableFuture集約
│       │   └── ProductService.java         # 商品CRUD
│       └── controller/         # REST APIエンドポイント
└── frontend/                   # React + Vite フロントエンド
    └── src/
        ├── api/index.js        # API呼出ラッパー
        └── components/         # ProductList, ProductDetail, OrderList
```

## 面接での発展的質問

### Kafkaリトライ & DLQ
- **リトライ可能な例外**（DBタイムアウト等）→ `order-events-retry` topicに送信、最大3回リトライ
- **リトライ不可な例外**（在庫不足）→ 失敗としてマーク、リトライしない
- **最大リトライ回数超過** → `order-events-dlq` デッドレターキューに送信、手動対応

### Topicパーティション数
- 本プロジェクトは **12パーティション** — 中規模に適切（Consumer数 ≤ パーティション数）
- 本番環境では一般的に **12 / 24 / 48** を使用、Consumerスループットに依存
- **ホットSKUボトルネック**：1つのSKUの全メッセージが同一パーティションに集中。「パーティション内サブバケット分割」または「ホットSKU専用topic」で解決
