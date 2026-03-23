-- ============================================================
-- 商品秒杀系统 - 数据库 Schema
-- 覆盖技术点：Outbox、幂等、原子扣库存
-- ============================================================

CREATE DATABASE IF NOT EXISTS `inventory_db` DEFAULT CHARACTER SET utf8mb4;
USE `inventory_db`;

-- 1. 商品表
CREATE TABLE `t_product` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `sku_id`      VARCHAR(64)  NOT NULL COMMENT '商品SKU编号',
    `name`        VARCHAR(128) NOT NULL COMMENT '商品名称',
    `price`       DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    `stock`       INT          NOT NULL DEFAULT 0 COMMENT '库存数量',
    `description` VARCHAR(512) DEFAULT '' COMMENT '商品描述',
    `image_url`   VARCHAR(256) DEFAULT '' COMMENT '图片URL',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sku_id` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 2. 订单表
CREATE TABLE `t_order` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `order_no`    VARCHAR(64)  NOT NULL COMMENT '订单号',
    `sku_id`      VARCHAR(64)  NOT NULL,
    `quantity`    INT          NOT NULL DEFAULT 1,
    `amount`      DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待处理 1=已完成 2=失败',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 3. Outbox 表 —— 事务性发件箱，保证业务写入+事件发布的原子性
--    技术点2: 在同一个MySQL事务里写业务表 + Outbox表
CREATE TABLE `t_outbox` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `aggregate_type` VARCHAR(64)  NOT NULL COMMENT '聚合类型，如 ORDER',
    `aggregate_id`   VARCHAR(64)  NOT NULL COMMENT '聚合ID，如 orderNo',
    `event_type`     VARCHAR(64)  NOT NULL COMMENT '事件类型，如 ORDER_CREATED',
    `payload`        TEXT         NOT NULL COMMENT '事件JSON内容',
    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待发布 1=已发布',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Outbox发件箱表';

-- 4. 已处理事件表 —— 消费端幂等去重
--    技术点3: INSERT IGNORE 保证重复消息不重复处理
CREATE TABLE `t_processed_event` (
    `event_id`     VARCHAR(64)  NOT NULL COMMENT '事件唯一ID',
    `processed_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已处理事件表(幂等)';

-- 5. 促销信息表 —— 用于演示 CompletableFuture 并发查询
CREATE TABLE `t_promotion` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `sku_id`      VARCHAR(64)  NOT NULL,
    `label`       VARCHAR(128) NOT NULL COMMENT '促销标签，如 限时8折',
    `discount`    DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '折扣率 0.80=8折',
    `start_time`  DATETIME     NOT NULL,
    `end_time`    DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_sku_id` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='促销信息表';

-- ============================================================
-- 初始测试数据
-- ============================================================
INSERT INTO `t_product` (`sku_id`, `name`, `price`, `stock`, `description`, `image_url`) VALUES
('SKU001', 'iPhone 15 Pro', 7999.00, 100, 'Apple iPhone 15 Pro 256GB', '/images/iphone15.jpg'),
('SKU002', 'MacBook Air M3', 8999.00, 50, 'Apple MacBook Air 15寸 M3', '/images/macbook.jpg'),
('SKU003', 'AirPods Pro 2', 1799.00, 200, 'Apple AirPods Pro 第二代', '/images/airpods.jpg');

INSERT INTO `t_promotion` (`sku_id`, `label`, `discount`, `start_time`, `end_time`) VALUES
('SKU001', '限时直降500', 0.94, '2024-01-01 00:00:00', '2099-12-31 23:59:59'),
('SKU003', '买一送耳套', 1.00, '2024-01-01 00:00:00', '2099-12-31 23:59:59');
