package com.example.inventory.controller;

import com.example.inventory.entity.Product;
import com.example.inventory.service.ProductDetailService;
import com.example.inventory.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductDetailService productDetailService;

    /** 商品列表 */
    @GetMapping
    public List<Product> list() {
        return productService.listAll();
    }

    /**
     * 商品详情（技术点5: CompletableFuture 并发聚合）
     * 同时查基本信息、实时库存、促销，带超时降级
     */
    @GetMapping("/{skuId}")
    public ResponseEntity<ProductDetailService.ProductDetailVO> detail(@PathVariable String skuId) {
        ProductDetailService.ProductDetailVO vo = productDetailService.getProductDetail(skuId);
        if (vo.getProduct() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(vo);
    }

    /** 新增商品 */
    @PostMapping
    public Product create(@RequestBody Product product) {
        return productService.create(product);
    }

    /** 更新商品 */
    @PutMapping("/{skuId}")
    public Product update(@PathVariable String skuId, @RequestBody Product product) {
        return productService.update(skuId, product);
    }

    /** 删除商品 */
    @DeleteMapping("/{skuId}")
    public ResponseEntity<Void> delete(@PathVariable String skuId) {
        productService.delete(skuId);
        return ResponseEntity.noContent().build();
    }
}
