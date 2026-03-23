package com.example.inventory.controller;

import com.example.inventory.entity.Order;
import com.example.inventory.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 下单接口（技术点1: 削峰填谷）
     * 请求 → Kafka → 异步消费扣库存，立即返回订单号
     */
    @PostMapping
    public OrderService.PlaceOrderResponse placeOrder(@RequestBody OrderService.PlaceOrderRequest request) {
        return orderService.placeOrder(request);
    }

    /** 查询订单状态 */
    @GetMapping("/{orderNo}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderNo) {
        return orderService.getOrder(orderNo)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 订单列表 */
    @GetMapping
    public List<Order> list() {
        return orderService.getAllOrders();
    }
}
