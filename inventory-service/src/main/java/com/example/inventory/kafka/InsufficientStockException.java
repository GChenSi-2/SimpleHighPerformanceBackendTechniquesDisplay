package com.example.inventory.kafka;

/**
 * 库存不足异常 —— 不可重试的业务异常
 */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String skuId) {
        super("库存不足: skuId=" + skuId);
    }
}
