package com.example.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 技术点5: 为 CompletableFuture 并发聚合提供线程池
 */
@Configuration
public class AsyncConfig {

    @Bean
    public ExecutorService queryExecutor() {
        return Executors.newFixedThreadPool(8);
    }
}
