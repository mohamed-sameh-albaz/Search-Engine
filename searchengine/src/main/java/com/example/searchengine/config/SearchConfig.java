package com.example.searchengine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class SearchConfig {

    @Value("${app.search.max-batch-size:100}")
    private int maxBatchSize;

    @Value("${app.search.default-page-size:10}")
    private int defaultPageSize;

    @Bean
    public ThreadPoolTaskExecutor searchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("search-executor-");
        executor.initialize();
        return executor;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }
} 